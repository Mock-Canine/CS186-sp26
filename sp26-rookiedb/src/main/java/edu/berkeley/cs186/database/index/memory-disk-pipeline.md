# How B+ Tree Nodes Move Between Memory and Disk

This document explains the classes and methods that simulate disk I/O for
`LeafNode` and `InnerNode`. If you understand this pipeline, you understand
how every byte a node writes ends up on "disk" and how it comes back.

---

## The Big Picture

```
LeafNode / InnerNode
   │  toBytes() ──→ byte[]
   │  fromBytes() ←── byte[]
   ▼
  Page            ← thin handle; delegates everything to a BufferFrame
   │  getBuffer() ──→ PageBuffer (positional read/write cursor)
   ▼
BufferFrame       ← owns the actual byte[4096]; tracks dirty flag + pin count
   │  readBytes() / writeBytes() ──→ System.arraycopy into byte[]
   ▼
BufferManager     ← page cache; maps pageNum → frame; runs eviction
   │  fetchPage()      ── read from disk into frame
   │  frame.flush()    ── write dirty frame back to disk
   ▼
DiskSpaceManager  ← virtual page numbers → OS file I/O
   │  readPage() / writePage()
   ▼
PartitionHandle   ← one RandomAccessFile per partition
   │  fileChannel.read() / fileChannel.write()
   ▼
  OS file on disk
```

A node never touches files directly. It serializes itself to a `byte[]`,
hands that to a `Page`, which writes it into a `BufferFrame`'s in-memory
array. Later — when the frame is evicted or explicitly flushed — the
`BufferManager` writes that array to disk through the `DiskSpaceManager`.

---

## Layer 1: Buffer (the read/write cursor)

**File:** `common/Buffer.java` (interface), `common/AbstractBuffer.java` (base impl)

`Buffer` is the interface every node uses to read and write bytes. It works
like `java.nio.ByteBuffer`: it holds an internal **position** that advances
automatically on each read or write.

```java
Buffer buf = page.getBuffer();   // position starts at 0
buf.put((byte) 1);               // write 1 byte, position → 1
buf.putLong(42L);                // write 8 bytes, position → 9
buf.putInt(3);                   // write 4 bytes, position → 13
byte b = buf.get();              // read 1 byte,  position → 14
int  n = buf.getInt();           // read 4 bytes, position → 18
```

### Key methods

| Method | Does |
|---|---|
| `get()` / `get(byte[])` | Read 1 byte / fill array, advance position |
| `getInt()` / `getLong()` / `getShort()` / `getFloat()` | Read 4/8/2/4 bytes as typed value |
| `put(byte)` / `put(byte[])` | Write 1 byte / array, advance position |
| `putInt()` / `putLong()` / `putShort()` / `putFloat()` | Write typed value |
| `position()` / `position(int)` | Get or set the cursor |

Internally, `AbstractBuffer` converts every typed read/write into raw `get`/`put`
of bytes. The actual byte storage is decided by whichever subclass implements
the abstract `get(byte[], int, int)` and `put(byte[], int, int)`.

---

## Layer 2: Page and PageBuffer

**File:** `memory/Page.java`

A `Page` is a lightweight handle that points to a `BufferFrame`. It does not
own the data — it just delegates.

### page.getBuffer()

Returns a **PageBuffer** (inner class of `Page`). This is the concrete
`Buffer` that nodes interact with. When you call `pageBuffer.put(bytes)`,
the call chain is:

```
PageBuffer.put(byte[] src, int offset, int length)
  → Page.writeBytes(offset, length, src)
    → frame.writeBytes(position, length, src)
      → System.arraycopy(src, 0, frame.contents, position + 36, length)
      → frame.dirty = true
```

The **+36** is critical: every data page reserves its first 36 bytes for
recovery metadata (the page LSN). User data starts at byte 36. This offset
is applied automatically inside the frame — node code never sees it.

Reads work the same way in reverse:

```
PageBuffer.get(byte[] dst, int offset, int length)
  → Page.readBytes(offset, length, dst)
    → frame.readBytes(position, length, dst)
      → System.arraycopy(frame.contents, position + 36, dst, 0, length)
```

### page.pin() / page.unpin()

A pinned page cannot be evicted from the buffer cache. The pin count tracks
how many pieces of code are currently using the page.

```java
page.pin();       // pinCount++ (page stays in memory)
// ... use page ...
page.unpin();     // pinCount-- (page now eligible for eviction)
```

**Rule:** Every `pin()` must be paired with an `unpin()`. If you forget to
unpin, the page is stuck in memory forever and eventually all frames are
pinned and the buffer manager throws an exception.

Both node constructors follow a `try/finally` pattern:

```java
// (page arrives already pinned from fetchNewPage or fetchPage)
try {
    this.page = page;
    this.keys = new ArrayList<>(keys);
    // ... copy data ...
    sync();          // serialize to page
} finally {
    page.unpin();    // always unpin when done
}
```

---

## Layer 3: BufferFrame (the actual byte array)

**File:** `memory/BufferFrame.java` (base), `BufferManager.java` inner class `Frame`

A `BufferFrame` wraps a `byte[4096]` — the raw page contents. Key state:

| Field | Purpose |
|---|---|
| `byte[] contents` | The 4096-byte array (same size as a disk page) |
| `long pageNum` | Which virtual page this frame holds |
| `boolean dirty` | Has this frame been written to since last flush? |
| `int pinCount` | Number of active pins (>0 means cannot evict) |
| `int index` | Position in the frame pool (negative = free/invalid) |

### readBytes / writeBytes

These are the lowest in-memory operations. Both temporarily pin the frame
(to prevent eviction mid-operation), copy bytes with `System.arraycopy`,
and notify the eviction policy that this frame was recently used.

```java
// Simplified writeBytes
void writeBytes(short position, short num, byte[] buf) {
    pin();
    try {
        int offset = position + 36;  // skip reserved space
        System.arraycopy(buf, 0, this.contents, offset, num);
        this.dirty = true;           // mark for future flush
    } finally {
        unpin();
    }
}
```

### flush()

Called when a dirty frame needs to go to disk (during eviction, shutdown,
or explicit flush). This is the only path from memory to disk:

```java
void flush() {
    if (!dirty) return;              // nothing to write
    recoveryManager.pageFlushHook(); // ensure log is flushed first (WAL)
    diskSpaceManager.writePage(pageNum, contents);  // write 4096 bytes
    dirty = false;
}
```

---

## Layer 4: BufferManager (the page cache)

**File:** `memory/BufferManager.java`

The buffer manager is a fixed-size pool of `BufferFrame` objects. It decides
which pages live in memory and which get evicted.

### Important constants

```java
RESERVED_SPACE      = 36;       // bytes reserved per page for recovery
EFFECTIVE_PAGE_SIZE = 4096 - 36 = 4060;  // bytes available for node data
```

`EFFECTIVE_PAGE_SIZE` is what the B+ tree uses to compute max order —
how many keys fit on a single page.

### fetchPage(lockContext, pageNum) — loading a page from disk

This is what `BPlusNode.fromBytes()` and `InnerNode.getChild()` call.

```
1. Check if pageNum is already in the cache (pageToFrame map)
   → YES: pin that frame and return it wrapped in a Page
   → NO:  continue to step 2

2. Find a frame to use:
   a. If there's a free frame in the pool, use it
   b. Otherwise, ask the eviction policy to pick a victim

3. If victim frame is dirty, flush it to disk first

4. Read the requested page from disk into the frame:
   diskSpaceManager.readPage(pageNum, frame.contents)

5. Pin the frame (pinCount = 1)

6. Return a new Page object wrapping this frame
```

### fetchNewPage(lockContext, partNum) — allocating a fresh page

This is what the node constructors call when creating a brand-new node.

```
1. Ask DiskSpaceManager to allocate a new page number on the partition
2. Find/evict a frame (same as fetchPage steps 2-3)
3. Zero out the frame's contents (fresh page)
4. Pin and return wrapped in a Page
```

### Eviction — ClockEvictionPolicy

**File:** `memory/ClockEvictionPolicy.java`

Uses the **clock (second-chance)** algorithm:

- Each frame has a "reference bit" (set on every access via `hit()`)
- A clock hand sweeps through frames
- On eviction: advance the hand, clear reference bits, skip pinned frames
- Evict the first unpinned frame with a cleared reference bit

```
Frame pool:  [A*] [B ] [C*] [D ] [E*]    (* = reference bit set)
                        ^arm

Eviction sweep:
  C* → clear bit, skip   → [A*] [B ] [C ] [D ] [E*]
  D  → not pinned, bit clear → EVICT D
```

If D is dirty, it gets flushed to disk before its contents are overwritten.

---

## Layer 5: DiskSpaceManager (virtual disk)

**File:** `io/DiskSpaceManager.java` (interface), `io/DiskSpaceManagerImpl.java` (impl)

### Virtual page numbers

A single `long` encodes both partition and page:

```
virtual page number = (partNum, pageNum)
                       high bits  low bits
```

Each B+ tree gets its own partition (from `metadata.getPartNum()`), so all
nodes of one tree live in the same OS file.

### readPage / writePage

```java
void readPage(long virtualPageNum, byte[] buf) {
    int partNum = getPartNum(virtualPageNum);
    int pageNum = getPageNum(virtualPageNum);
    PartitionHandle partition = partitions.get(partNum);
    partition.readPage(pageNum, buf);   // → fileChannel.read()
}

void writePage(long virtualPageNum, byte[] buf) {
    // same routing
    partition.writePage(pageNum, buf);  // → fileChannel.write() + force()
}
```

### PartitionHandle — one file per partition

**File:** `io/PartitionHandle.java`

Each partition is a `RandomAccessFile`. Pages are stored at calculated offsets
within the file (accounting for header/master pages that track allocation).
`readPage` and `writePage` use `FileChannel` for positioned I/O:

```java
fileChannel.read(ByteBuffer.wrap(buf), dataPageOffset(pageNum));
fileChannel.write(ByteBuffer.wrap(buf), dataPageOffset(pageNum));
fileChannel.force(false);  // fsync — ensures bytes hit disk
```

---

## Putting It All Together: What sync() Does

Both `LeafNode` and `InnerNode` have a `sync()` method that is the bridge
between the node's in-memory Java objects and the page on disk:

```java
// LeafNode.sync() — InnerNode.sync() is identical
private void sync() {
    page.pin();                          // 1. prevent eviction
    try {
        Buffer b = page.getBuffer();     // 2. get cursor at position 0
        byte[] newBytes = toBytes();     // 3. serialize node to byte[]
        byte[] bytes = new byte[newBytes.length];
        b.get(bytes);                    // 4. read current page contents
        if (!Arrays.equals(bytes, newBytes)) {
            page.getBuffer().put(toBytes());  // 5. write only if changed
        }
    } finally {
        page.unpin();                    // 6. allow eviction again
    }
}
```

Step 5 writes into the frame's `byte[4096]` (at offset 36+) and sets
`dirty = true`. The actual disk write happens later, when the frame is
evicted or flushed.

### sync() is called:
- In both node **constructors** (after setting fields)
- After any **mutation** (put, remove, bulkLoad) — your implementation must call it

---

## Putting It All Together: What fromBytes() Does

`fromBytes` is the reverse of `sync`: it reads a page from the cache (or
disk) and reconstructs a node object.

**InnerNode.fromBytes** (already implemented — use as reference):

```java
public static InnerNode fromBytes(BPlusTreeMetadata metadata,
        BufferManager bufferManager, LockContext treeContext, long pageNum) {

    Page page = bufferManager.fetchPage(treeContext, pageNum);
    // fetchPage either:
    //   - returns cached frame (fast path), or
    //   - evicts a victim, reads from disk, returns new frame
    // Either way, the page is now PINNED.

    Buffer buf = page.getBuffer();    // cursor at position 0

    byte nodeType = buf.get();        // read 1 byte (0x00 for inner)
    assert(nodeType == (byte) 0);

    int n = buf.getInt();             // read 4 bytes: number of keys

    List<DataBox> keys = new ArrayList<>();
    for (int i = 0; i < n; ++i) {
        keys.add(DataBox.fromBytes(buf, metadata.getKeySchema()));
        // reads keySize bytes, advances cursor
    }

    List<Long> children = new ArrayList<>();
    for (int i = 0; i < n + 1; ++i) {
        children.add(buf.getLong());   // read 8 bytes each
    }

    // Constructor stores fields, calls sync(), then unpins
    return new InnerNode(metadata, bufferManager, page, keys, children, treeContext);
}
```

The key insight: `DataBox.fromBytes(buf, type)` needs the **Type** to know
how many bytes to read. The type comes from `metadata.getKeySchema()`, not
from the page itself. DataBox serialization is type-unaware — it writes raw
values with no type tag.

### BPlusNode.fromBytes — the dispatcher

When you don't know whether a page holds a leaf or inner node:

```java
public static BPlusNode fromBytes(..., long pageNum) {
    Page p = bufferManager.fetchPage(treeContext, pageNum);
    Buffer buf = p.getBuffer();
    byte b = buf.get();           // peek at first byte
    p.unpin();
    if (b == 1) return LeafNode.fromBytes(..., pageNum);  // re-fetches page
    if (b == 0) return InnerNode.fromBytes(..., pageNum);
}
```

This reads the page twice (once to check type, once in the specific
`fromBytes`). That's fine — the second `fetchPage` hits the cache.

---

## The Page Layout on Disk

```
byte offset in frame.contents[]:

 0                    36                                          4095
 ├── reserved (36B) ──┤────────── user data (4060B) ──────────────┤
 │  [8B unused]       │  what toBytes() produces goes here        │
 │  [8B pageLSN]      │  LeafNode: [1B][8B][4B][entries...]       │
 │  [20B recovery]    │  InnerNode: [1B][4B][keys...][children...] │
 └────────────────────┴───────────────────────────────────────────┘

 Node code writes to position 0 of the Buffer.
 The frame automatically adds +36 offset.
 Node code never sees the reserved bytes.
```

---

## Lifecycle Summary

### Creating a new node (write path)

```
new LeafNode(metadata, bufferManager, keys, rids, sibling, ctx)
  │
  ├─ bufferManager.fetchNewPage(ctx, partNum)
  │   ├─ diskSpaceManager.allocPage(partNum)  → new virtual page number
  │   ├─ find free frame or evict victim (flush if dirty)
  │   ├─ zero out frame contents
  │   └─ return Page (pinned, pinCount=1)
  │
  ├─ store keys, rids, sibling in Java fields
  │
  ├─ sync()
  │   ├─ page.pin()                 (pinCount=2)
  │   ├─ toBytes() → byte[]        (serialize Java fields)
  │   ├─ page.getBuffer().put(...)  (copy into frame.contents[36..])
  │   │   └─ frame.dirty = true
  │   └─ page.unpin()               (pinCount=1)
  │
  └─ page.unpin()                   (pinCount=0, eligible for eviction)

  ... later, when frame is evicted or flushed ...

  frame.flush()
    └─ diskSpaceManager.writePage(pageNum, frame.contents)
        └─ partitionHandle.writePage(pageNum, contents)
            └─ fileChannel.write(...)   ← bytes hit the OS file
```

### Loading an existing node (read path)

```
LeafNode.fromBytes(metadata, bufferManager, ctx, pageNum)
  │
  ├─ bufferManager.fetchPage(ctx, pageNum)
  │   ├─ check cache: pageToFrame.get(pageNum)
  │   │   found → pin that frame, return Page
  │   │   not found ↓
  │   ├─ find free frame or evict victim (flush if dirty)
  │   ├─ diskSpaceManager.readPage(pageNum, frame.contents)
  │   │   └─ partitionHandle.readPage(...)
  │   │       └─ fileChannel.read(...)   ← bytes come from OS file
  │   └─ return Page (pinned)
  │
  ├─ page.getBuffer() → PageBuffer at position 0
  ├─ read isLeaf byte, rightSibling long, numEntries int
  ├─ loop: read each key (DataBox.fromBytes) and rid (RecordId.fromBytes)
  │
  └─ new LeafNode(metadata, bufferManager, page, keys, rids, sibling, ctx)
      ├─ store fields
      ├─ sync()    (writes back — usually a no-op since data matches)
      └─ page.unpin()
```

---

## Common Pitfalls

1. **Forgetting to unpin:** Every `pin()` or `fetchPage`/`fetchNewPage` must
   have a matching `unpin()`. Use try/finally. If all frames are pinned, the
   buffer manager throws "cannot evict — everything pinned".

2. **Stale in-memory cache:** If you create two node objects for the same
   page, modifying one does NOT update the other's `keys`/`rids` lists.
   The disk copy is updated (via sync), but the other object's Java fields
   are stale. Always re-call `fromBytes` to get fresh data.

3. **Not calling sync() after mutation:** If you modify `keys` or `rids`
   but don't call `sync()`, the changes exist only in Java heap memory.
   They are not written to the page's frame and will be lost on eviction.

4. **Using the wrong constructor in fromBytes:** The first `LeafNode`
   constructor calls `fetchNewPage` (allocates a brand-new page). The
   `fromBytes` method must use the second (private) constructor that accepts
   an existing `Page` — otherwise you'd allocate a new page every time you
   read an existing node.

---

## Bonus: BPlusTree and BPlusTreeMetadata

The previous sections covered the low-level pipeline (nodes → pages → frames
→ disk). This section covers the two classes that sit *above* the nodes and
manage the tree as a whole.

---

### BPlusTreeMetadata — the tree's configuration record

**File:** `index/BPlusTreeMetadata.java`

This is a plain data object that holds everything needed to open an existing
tree or create a new one. It has no connection to pages or buffers — it lives
in a regular database table.

#### Fields

```java
String tableName;   // e.g. "Students"          — which table this index is for
String colName;     // e.g. "gpa"               — which column is the search key
Type   keySchema;   // e.g. Type.floatType()    — type of the indexed column
int    order;       // e.g. 5                   — the "d" in B+ tree of order d
int    partNum;     // e.g. 7                   — disk partition for all tree pages
long   rootPageNum; // e.g. 42                  — page number of the current root node
int    height;      // e.g. 3                   — number of levels in the tree
```

`order` determines capacity: each inner node holds d–2d keys, each leaf
holds d–2d (key, rid) pairs. It is computed once at index creation and never
changes.

`rootPageNum` and `height` are the only mutable fields — they change when
the root splits. Everything else is fixed for the lifetime of the index.

#### How metadata is persisted

Metadata is NOT stored on B+ tree pages. It lives in a normal database table
called `_metadata.indices` (partition 2). The schema is:

```
table_name (string32) | col_name (string32) | order (int) | part_num (int)
root_page_num (long)  | key_schema_typeid (int) | key_schema_typesize (int) | height (int)
```

Two methods convert between BPlusTreeMetadata and table rows:

```java
// Serialize: metadata → Record (a row in _metadata.indices)
public Record toRecord() {
    return new Record(tableName, colName, order, partNum, rootPageNum,
            keySchema.getTypeId().ordinal(), keySchema.getSizeInBytes(), height);
}

// Deserialize: Record → metadata
public BPlusTreeMetadata(Record record) {
    this.tableName  = record.getValue(0).getString();
    this.colName    = record.getValue(1).getString();
    this.order      = record.getValue(2).getInt();
    this.partNum    = record.getValue(3).getInt();
    this.rootPageNum = record.getValue(4).getLong();
    // reconstruct Type from typeId ordinal + size
    int typeIdIndex = record.getValue(5).getInt();
    int typeSize    = record.getValue(6).getInt();
    this.keySchema  = new Type(TypeId.values()[typeIdIndex], typeSize);
    this.height     = record.getValue(7).getInt();
}
```

#### How metadata flows through the system

```
Database.createIndex("Students", "gpa")
  │
  ├─ compute order = BPlusTree.maxOrder(EFFECTIVE_PAGE_SIZE, colType)
  ├─ allocate a disk partition for the new tree
  ├─ build a Record with (tableName, colName, order, partNum, INVALID_PAGE_NUM, ...)
  ├─ insert that Record into _metadata.indices table
  ├─ construct BPlusTreeMetadata from the Record
  └─ new BPlusTree(bufferManager, metadata, lockContext)
       └─ rootPageNum is INVALID → creates an empty LeafNode as root
       └─ calls updateRoot() → sets rootPageNum, increments height
       └─ calls transaction.updateIndexMetadata(metadata)
            └─ metadata.toRecord() → update the row in _metadata.indices
```

Later, when the database restarts and needs to reopen the index:

```
scanIndexMetadata()                    — reads all rows from _metadata.indices
  → new BPlusTreeMetadata(record)      — reconstruct from each row
  → new BPlusTree(bufferManager, metadata, lockContext)
       └─ rootPageNum is valid → BPlusNode.fromBytes(metadata, ..., rootPageNum)
            └─ loads the root from disk using the saved page number
```

#### What nodes use metadata for

Every node receives the same `BPlusTreeMetadata` instance. They use it for:

| Field accessed | Used by | Purpose |
|---|---|---|
| `getKeySchema()` | `DataBox.fromBytes(buf, metadata.getKeySchema())` | Know how many bytes to read per key |
| `getOrder()` | `put`, `bulkLoad`, `toBytes` assertions | Enforce capacity: max 2d entries |
| `getPartNum()` | `new LeafNode(...)`, `new InnerNode(...)` constructors | Allocate new pages on the right partition |

Nodes never call `setRootPageNum` or `incrementHeight` — only `BPlusTree` does that.

---

### BPlusTree — the public API and root manager

**File:** `index/BPlusTree.java`

This is the entry point for all index operations. It holds a reference to
the `root` node and delegates tree traversal to the node classes.

#### Fields

```java
BufferManager bufferManager;       // for fetching/creating pages
BPlusTreeMetadata metadata;        // tree configuration (order, key type, root page, etc.)
BPlusNode root;                    // the current root node (InnerNode or LeafNode)
LockContext lockContext;           // concurrency control for the whole tree
```

#### Constructor — opening or creating a tree

```java
public BPlusTree(BufferManager bufferManager, BPlusTreeMetadata metadata,
                 LockContext lockContext) {
    // Lock the entire tree (no per-node locks)
    lockContext.disableChildLocks();
    LockUtil.ensureSufficientLockHeld(lockContext, LockType.S);

    // Validate order
    int maxOrder = BPlusTree.maxOrder(EFFECTIVE_PAGE_SIZE, metadata.getKeySchema());
    if (metadata.getOrder() > maxOrder) throw ...;

    if (metadata.getRootPageNum() != INVALID_PAGE_NUM) {
        // EXISTING TREE: load root from disk
        this.root = BPlusNode.fromBytes(metadata, bufferManager,
                                         lockContext, metadata.getRootPageNum());
    } else {
        // NEW TREE: create an empty leaf as the root
        LockUtil.ensureSufficientLockHeld(lockContext, LockType.X);
        this.updateRoot(new LeafNode(metadata, bufferManager,
                                      new ArrayList<>(), new ArrayList<>(),
                                      Optional.empty(), lockContext));
    }
}
```

The key distinction: `INVALID_PAGE_NUM` (-1) means no root exists yet —
this is a brand-new index. Any other value is a valid page number to load.

#### updateRoot — the only place root/height change

```java
private void updateRoot(BPlusNode newRoot) {
    this.root = newRoot;
    metadata.setRootPageNum(this.root.getPage().getPageNum());
    metadata.incrementHeight();

    // Persist the updated metadata to _metadata.indices
    TransactionContext transaction = TransactionContext.getTransaction();
    if (transaction != null) {
        transaction.updateIndexMetadata(metadata);
    }
}
```

This method is called in exactly two situations:
1. **Tree creation** — the constructor creates an empty LeafNode root
2. **Root split** — your `put`/`bulkLoad` implementation must call it when
   the root node overflows

The persistence flow:

```
updateRoot(newRoot)
  ├─ metadata.setRootPageNum(newRoot.getPage().getPageNum())
  ├─ metadata.incrementHeight()
  └─ transaction.updateIndexMetadata(metadata)
       └─ Database.updateIndexMetadata(metadata)
            ├─ metadata.toRecord()  → Record with updated rootPageNum + height
            └─ indexMetadata.updateRecord(rid, updatedRecord)
                 └─ writes to _metadata.indices table on disk
```

Without this, the database would lose track of which page is the root after
restart.

#### Core API methods (all TODO(proj2))

Each method follows the same pattern: check the key type, ensure the right
lock, then delegate to the root node.

```java
// POINT LOOKUP
public Optional<RecordId> get(DataBox key) {
    typecheck(key);                              // key type must match keySchema
    LockUtil.ensureSufficientLockHeld(...);      // at least S lock
    // TODO: root.get(key).getKey(key)
    //       └─ traverses inner nodes down to the leaf, then looks up key
}

// INSERT
public void put(DataBox key, RecordId rid) {
    typecheck(key);
    LockUtil.ensureSufficientLockHeld(...);      // needs X lock
    // TODO: root.put(key, rid)
    //       if returns Optional.of(splitKey, newPageNum):
    //         create new root InnerNode with [old root, splitKey, new node]
    //         call updateRoot(newRoot)
}

// DELETE
public void remove(DataBox key) {
    typecheck(key);
    LockUtil.ensureSufficientLockHeld(...);
    // TODO: root.remove(key)
    //       no rebalancing, no root change needed
}
```

#### Handling root splits in put/bulkLoad

When you implement `put`, the critical logic is:

```
Optional<Pair<DataBox, Long>> result = root.put(key, rid);
if (result.isPresent()) {
    // The root split!
    DataBox splitKey = result.get().getFirst();
    long newPageNum = result.get().getSecond();

    // Create a new root with:
    //   - keys:     [splitKey]
    //   - children: [old root's page, new node's page]
    List<DataBox> newKeys = Arrays.asList(splitKey);
    List<Long> newChildren = Arrays.asList(root.getPage().getPageNum(), newPageNum);
    InnerNode newRoot = new InnerNode(metadata, bufferManager,
                                       newKeys, newChildren, lockContext);
    updateRoot(newRoot);
}
```

This is the only place the tree grows taller. Every other split is handled
recursively inside the node classes — a child split returns the split info
to its parent, which inserts the new key/pointer. Only when the root itself
splits does BPlusTree need to act.

#### Scan iterators — BPlusTreeIterator

```java
private class BPlusTreeIterator implements Iterator<RecordId> {
    // TODO(proj2): Add fields and implement hasNext/next
}
```

`scanAll()` and `scanGreaterEqual()` must return a `BPlusTreeIterator` that
lazily walks the leaf linked list. The iterator needs to track:
- The current `LeafNode` (or its page number)
- The current position within that leaf's `rids` list
- When the current leaf is exhausted, follow `rightSibling` to the next leaf

It must NOT load all rids into a list — that defeats the purpose of the
iterator and scores 0 points.

#### typecheck — runtime key validation

```java
private void typecheck(DataBox key) {
    Type t = metadata.getKeySchema();
    if (!key.type().equals(t)) {
        throw new IllegalArgumentException(...);
    }
}
```

Called at the start of `get`, `put`, `remove`, and `scanGreaterEqual`. This
prevents inserting an `IntDataBox` into a tree that indexes `StringDataBox`
values. The expected type comes from `metadata.getKeySchema()`.

#### maxOrder — computing the largest possible order

```java
public static int maxOrder(short pageSize, Type keySchema) {
    int leafOrder  = LeafNode.maxOrder(pageSize, keySchema);
    int innerOrder = InnerNode.maxOrder(pageSize, keySchema);
    return Math.min(leafOrder, innerOrder);
}
```

Takes the minimum of leaf and inner max orders because both node types must
fit on the same size page. Called by `Database.createIndex()` with
`BufferManager.EFFECTIVE_PAGE_SIZE` (4060 bytes) — not the raw 4096, since
36 bytes are reserved for recovery.

#### Locking strategy

```java
lockContext.disableChildLocks();
```

The entire B+ tree is locked as one unit — there are no per-node or per-page
locks. The constructor calls `disableChildLocks()` to enforce this. Read
operations (get, scan) need an S lock; write operations (put, remove,
bulkLoad) need an X lock.

The `TODO(proj4_integration)` comments mark where `LockType.NL` must be
replaced with the correct lock type in a later project.

#### Debug/visualization helpers

```java
// S-expression: nested tree as text
tree.toSexp();
// → "((1 (1 1)) (2 (2 2))) 3 ((3 (3 3)) (4 (4 4))))"

// DOT graph: pipe to `dot -T pdf tree.dot -o tree.pdf`
tree.toDot();

// Convenience: writes DOT file and converts to PDF
tree.toDotPDFFile("tree.pdf");
```

These delegate recursively to each node's `toSexp()`/`toDot()` — useful for
visualizing the tree while debugging your implementation.

---

### How BPlusTree, metadata, and nodes relate

```
Database
  │ owns _metadata.indices table (stores BPlusTreeMetadata as rows)
  │
  ├─ createIndex() → builds metadata → new BPlusTree(bufferManager, metadata, ctx)
  │                                          │
  │                                          ▼
  │                                     BPlusTree
  │                                       │ holds: root, metadata, bufferManager
  │                                       │
  │                                       ├─ get/put/remove/scan → delegates to root
  │                                       │
  │                                       ├─ updateRoot(newRoot)
  │                                       │   ├─ metadata.setRootPageNum(...)
  │                                       │   ├─ metadata.incrementHeight()
  │                                       │   └─ transaction.updateIndexMetadata(metadata)
  │                                       │        └─ writes updated row to _metadata.indices
  │                                       │
  │                                       └─ root: BPlusNode
  │                                            │ holds: metadata (read-only access)
  │                                            │
  │                                            ├─ uses metadata.getKeySchema()
  │                                            │   → to serialize/deserialize DataBox keys
  │                                            │
  │                                            ├─ uses metadata.getOrder()
  │                                            │   → to enforce node capacity (max 2d entries)
  │                                            │
  │                                            └─ uses metadata.getPartNum()
  │                                                → to allocate new pages on the right partition
  │
  └─ on restart: scanIndexMetadata() → reconstruct metadata from rows → reopen BPlusTrees
```

The separation is clean: **BPlusTreeMetadata** knows the configuration,
**BPlusTree** manages the root and API, **nodes** handle the actual tree
structure, and **Database** persists everything across restarts.