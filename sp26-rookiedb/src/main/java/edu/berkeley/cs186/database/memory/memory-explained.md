# memory/ Module -- Buffer Management (Page Cache)

## Overview

This module implements the **buffer pool** that sits between the rest of the database and the `DiskSpaceManager`. Pages are cached in fixed-size byte arrays called *frames*. Higher-level code interacts with lightweight `Page` handles that transparently pin/load pages on demand.

---

## The 36-Byte Reserved Region

Every non-log page has a hidden **36-byte header** at the start of its 4096-byte frame, reserved for recovery bookkeeping:

```
bytes 0-7   : (unused / available)
bytes 8-15  : pageLSN (the LSN of the last write to this page)
bytes 16-35 : (padding to guarantee a half-page redo/undo record fits in one log page)
```

```java
public static final short RESERVED_SPACE      = 36;
public static final short EFFECTIVE_PAGE_SIZE  = 4096 - 36 = 4060;
```

All user-visible offsets are relative to byte 36. The translation happens inside `Frame.dataOffset()`: every `readBytes(position, ...)` and `writeBytes(position, ...)` call adds `RESERVED_SPACE` before touching the underlying array. **Log pages are the exception** -- they use offset 0 and expose the full 4096 bytes, detected via `partNum == LogManager.LOG_PARTITION`.

---

## BufferManager

### Construction

```java
new BufferManager(diskSpaceManager, recoveryManager, bufferSize, evictionPolicy)
```

Allocates `bufferSize` Frame objects, each backed by a `new byte[4096]`. All start as "free" and are linked into a free list via bitwise complement of the next-free index (see Frame lifecycle below).

### Core Data Structures

| Field | Purpose |
|---|---|
| `Frame[] frames` | Fixed-size array of all buffer frames |
| `Map<Long, Integer> pageToFrame` | Virtual page number to frame index lookup |
| `int firstFreeIndex` | Head of the singly-linked free list threaded through unused frames |
| `EvictionPolicy evictionPolicy` | Pluggable replacement strategy |

### `fetchPageFrame(pageNum)` -- The Central Path

This is the method everything funnels through:

1. **Already cached?** If `pageToFrame` contains the page, pin the existing frame and return it.
2. **Free frame available?** If `firstFreeIndex < frames.length`, pop from the free list (`setUsed()`).
3. **Must evict.** Ask `evictionPolicy.evict(frames)` for a victim. Remove the victim from `pageToFrame` and call `cleanup`.
4. **Create new Frame** reusing the victim's `byte[]` array (avoids allocation). Initialize eviction policy with `init(newFrame)`.
5. **Flush the old frame** (`invalidate()` flushes if dirty, then marks invalid).
6. **Read the page** from disk into the frame's byte array via `diskSpaceManager.readPage(pageNum, contents)`.
7. Return the frame, pinned.

The manager lock is released *before* the disk I/O in steps 5-6, so other threads can proceed with already-cached pages while a disk read is in progress.

### `fetchNewPageFrame(partNum)`

Calls `diskSpaceManager.allocPage(partNum)` to get a fresh virtual page number, then calls `fetchPageFrame` to load it (the disk space manager has already zeroed the page on disk).

### Page Freeing

`freePage(Page)` evicts the frame, marks it free, and calls `diskSpaceManager.freePage`. `freePart(int)` scans all frames for the given partition, flushes and frees each one, then frees the partition on disk.

---

## Frame -- The Inner Class

`Frame` is a non-static inner class of `BufferManager`, so it has access to the manager's fields (disk space manager, eviction policy, recovery manager).

### Lifecycle States

A frame is always in one of three states, encoded by the `index` field:

| State | `index` value | Meaning |
|---|---|---|
| **Valid** | >= 0 | Holds a loaded page; `index` is its position in `frames[]` |
| **Free** | < 0, != MIN_VALUE | Available for reuse; `~index` is the next free frame's index (singly-linked list) |
| **Invalid** | `Integer.MIN_VALUE` | Evicted or discarded; `contents` is null; any `Page` still holding this frame will get an error on access |

The free list is threaded through the `index` field using bitwise complement (`~`), which maps 0 to -1, 1 to -2, etc. This avoids needing a separate `nextFree` pointer.

### Pin/Unpin and Locking

`Frame.pin()` acquires `frameLock` (a ReentrantLock) and increments `pinCount`. `Frame.unpin()` decrements the count and releases the lock. This means **a pinned frame holds the lock for its entire pin duration**, preventing concurrent eviction. The eviction policy checks `isPinned()` and skips pinned frames.

### Read/Write and the Offset Translation

```java
void readBytes(short position, short num, byte[] buf) {
    System.arraycopy(this.contents, position + dataOffset(), buf, 0, num);
    evictionPolicy.hit(this);
}

void writeBytes(short position, short num, byte[] buf) {
    // ... log the write via recoveryManager ...
    System.arraycopy(buf, 0, this.contents, position + dataOffset(), num);
    this.dirty = true;
    evictionPolicy.hit(this);
}
```

`dataOffset()` returns `RESERVED_SPACE` (36) for normal pages, 0 for log pages. Both methods notify the eviction policy on every access.

### Write-Ahead Logging in writeBytes

Before modifying the byte array, `writeBytes` compares old and new content byte-by-byte using `getChangedBytes()`, which produces a list of `(offset, length)` ranges where actual changes occur. For each range, it extracts the before-image and after-image, and calls `recoveryManager.logPageWrite(...)`. The returned LSN is stored as the page's `pageLSN`. This happens only when a transaction is active and the page is not a log page.

The `getChangedBytes` method merges nearby changed ranges (gap < `RESERVED_SPACE` = 36 bytes) and caps each range at `EFFECTIVE_PAGE_SIZE / 2` = 2030 bytes, so each log record stays within size limits.

### Flush

`flush()` writes the frame's byte array to disk if dirty, calling `recoveryManager.pageFlushHook(pageLSN)` first (to ensure WAL -- the log record must be on disk before the data page). Log pages skip this hook since they *are* the log.

---

## Page -- The Lightweight Handle

`Page` is what the rest of the database holds onto. It wraps a `BufferFrame` reference and a `LockContext`.

### Key Insight: Stale Handle Recovery

When a `Page` is unpinned and its frame gets evicted, the frame is marked **invalid**. A subsequent `page.pin()` calls `frame.requestValidFrame()`, which detects the invalid state and calls `BufferManager.fetchPageFrame(pageNum)` to reload the page into a (possibly different) frame. The `Page` object's `frame` field is then updated. This is why `Page` is a *handle*, not a container -- it survives eviction.

### PageBuffer -- The NIO-Style Wrapper

`page.getBuffer()` returns a `PageBuffer` (extends `AbstractBuffer`) that translates NIO-style operations (`get`, `put`, `getInt`, `putInt`, position tracking) into `Page.readBytes` / `Page.writeBytes` calls.

### Complete Write Path

```
page.getBuffer().putInt(42)
  --> PageBuffer.put(byte[], offset, length)
      --> Page.writeBytes(position, num, buf)       // bounds check
          --> Frame.writeBytes(position, num, buf)   // adds +36 offset, logs, arraycopy
              --> System.arraycopy(buf, 0, contents, position+36, num)
              --> dirty = true
```

Later, when the frame is evicted or explicitly flushed:

```
Frame.flush()
  --> recoveryManager.pageFlushHook(pageLSN)   // ensure log is on disk first
  --> diskSpaceManager.writePage(pageNum, contents)   // full 4096-byte write
```

---

## Eviction Policies

Both policies implement the `EvictionPolicy` interface:

```java
void init(BufferFrame frame);        // called when frame is first loaded
void hit(BufferFrame frame);         // called on every read/write
BufferFrame evict(BufferFrame[] frames);  // choose a victim
void cleanup(BufferFrame frame);     // called when frame is removed
```

### ClockEvictionPolicy (Second-Chance)

Uses `BufferFrame.tag` as a reference bit (`true` = recently used, `null` = not).

- **hit**: set tag to `ACTIVE` (true).
- **evict**: starting from the `arm` position, scan the frame array circularly. If a frame is `ACTIVE` or pinned, set it to `INACTIVE` (null) and advance. Stop at the first `INACTIVE`, unpinned frame. After two full passes, if no victim is found, everything is pinned -- throw.

The choice of `null` (not `false`) as `INACTIVE` is deliberate: new frames have `tag == null` before any policy sees them, so they are immediately evictable without special initialization. This is why `init()` and `cleanup()` are no-ops.

### LRUEvictionPolicy

Maintains a **doubly-linked list** of `Tag` nodes threaded through `BufferFrame.tag`, ordered from least-recently to most-recently used. Sentinel nodes `listHead` and `listTail` simplify insertion/removal.

- **init**: insert at tail (most recent).
- **hit**: unlink and re-insert at tail.
- **evict**: walk from head, skip pinned frames, return the first unpinned.
- **cleanup**: unlink from list.

---

## Cross-Module Relationships

1. **BufferManager owns DiskSpaceManager**: all disk reads/writes for data pages flow through the buffer manager's `fetchPageFrame` and `Frame.flush`. No other module calls `diskSpaceManager.readPage/writePage` directly.

2. **PartitionHandle references BufferManager constants**: `freePage` in the io module uses `BufferManager.RESERVED_SPACE` and `BufferManager.EFFECTIVE_PAGE_SIZE` to split undo log records. This is the one place the disk layer reaches back into the memory layer's constants.

3. **Recovery is woven in everywhere**: `Frame.writeBytes` logs before/after images, `Frame.flush` calls `pageFlushHook` to enforce WAL, and `PartitionHandle` logs alloc/free events. The `pageLSN` stored at bytes 8-15 of each frame connects pages to the log sequence.

4. **Page handles survive eviction**: a `Page` object can outlive the frame it was originally loaded into. `requestValidFrame()` transparently reloads the page, making `Page` a stable reference for upper layers (B+ tree nodes, table pages, etc.) regardless of buffer pool churn.
