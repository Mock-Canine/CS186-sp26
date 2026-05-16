# CS186 RookieDB — Session Notes

---

**Q: In what situation will an InnerNode's children list be empty?**

In a valid B+ tree, it never should be. An InnerNode with `n` keys always has `n+1` children, so the minimum is 1 child (0 keys) — which would only occur transiently during construction.

The `assert(children.size() > 0)` in `getLeftmostLeaf()` is purely a defensive sanity check. If it ever fires, it means `fromBytes` deserialized incorrectly (read 0 children), or an InnerNode was constructed improperly outside of a split.

In normal operation (splits, inserts, root promotion), an InnerNode is only created when a leaf or inner node overflows and produces at least 2 children, so `children` will always have ≥ 2 entries in practice.

---

**Q: How to implement bulkLoad()? I'm confused — when BPlusTree calls bulkLoad, the root is a LeafNode, but when it overflows it needs to create non-existent InnerNodes, but unlike put it can't return its result to an InnerNode.**

Your confusion is the key insight: **LeafNode.bulkLoad() does NOT create InnerNodes.** It works exactly like `put()` — it just returns `Optional<Pair<DataBox, Long>>` when it overflows, and the caller handles the tree restructuring.

Mental model broken down by layer:

**LeafNode.bulkLoad()**
- Keep inserting items from the iterator one by one
- Stop and split when `keys.size()` exceeds `ceil(2d * fillFactor)`
- Return the split pair exactly like `put()` does — let the caller worry about what to do with it

**InnerNode.bulkLoad()**
- Call `bulkLoad()` on the **rightmost child** (always rightmost, since data is sorted)
- If a split is returned, absorb it (insert the split key + new child pointer into self)
- If absorbing causes self to overflow, split self and return up
- Keep doing this until the iterator is exhausted or self overflows

**BPlusTree.bulkLoad()**
- Call `root.bulkLoad()` in a loop
- If a split bubbles up to the root level, call `updateRoot()` — same as `put()`
- Keep looping until the iterator is exhausted

The crucial realization: **the iterator is shared across all calls**. Each call consumes items until one overflow happens and returns. Then the caller reshapes the tree and calls again. The rightmost path always receives the next batch.

---

**Q: When we use a B+ tree index, will we use a page directory in the heap file?**

Yes. The B+ tree index and the heap file (with its PageDirectory) are **separate structures** that coexist:

- **Heap file (PageDirectory)**: stores the actual records — always present regardless of whether an index exists
- **B+ tree index**: stores `(key → RecordId)` mappings, where `RecordId = (pageNum, slotNum)` points into the heap file

When you do a lookup via the index:
1. B+ tree finds the `RecordId` for your key
2. That `RecordId` is used to fetch the actual record from the heap file via the PageDirectory

---

**Q: But why do we need the page directory? We have pageNum which contains the partitionNum and pageNum, we can easily find the corresponding file and page.**

The PageDirectory is needed for **writes**, not just reads.

For **reads**, you're right — given a `RecordId` you can go directly to the page and slot without the PageDirectory.

But for **inserts**, you need to find a page with enough free space to fit the new record. Without the PageDirectory, you'd have to scan every page in the heap file checking free space — O(n) pages. The PageDirectory is a header structure that tracks free space per data page, so you can find a suitable page in O(1).

So the PageDirectory solves the **free space management** problem, not the lookup problem.

---

**Q: So in BPlusTree, if we want to put (insert) an item, we need to get the RecordId in advance, which means we insert the actual data into the heap file first, then update the index?**

Exactly. The flow is:

1. `Table.addRecord(record)` → inserts into heap file via PageDirectory → gets back a `RecordId`
2. `BPlusTree.put(key, recordId)` → inserts `(key → RecordId)` into the index

The index is always updated **after** the heap file insert, because you need the `RecordId` first. You can see this wiring in `Table.addRecord()` — it calls the B+ tree put at the end with the newly assigned `RecordId`.

---

**Q: What is the point of scanAll()? If I want to get all the record information in the table, I can directly reach the data pages and get all of them.**

`scanAll()` returns records in **sorted key order**, which a heap file scan cannot guarantee.

Heap file pages are unordered — records come back in insertion order. `scanAll()` walks the B+ tree's leaf linked list left to right, which gives you all `RecordId`s in sorted key order. This is useful for:

- `ORDER BY` on an indexed column — avoid a separate sort step
- Range scans (`scanGreaterEqual`) — start at the right leaf, walk right
- Merge join — needs sorted input from both sides

So it's not about *what* you get, it's about getting it **in order** without sorting.

---

**Q: So when we do bulkloading, the first step is to get sorted (key, RecordId) pairs as an iterator in RAM, sorted based on key. Before that, we need to sort the heap file (for clustered index), use out-of-core sort to generate the pairs, and call bulkLoad() to the BPlusTree?**

Not quite — a few corrections:

**You don't sort the heap file itself.** The heap file stays as-is (unordered). Instead:

1. Scan the heap file → collect `(key, RecordId)` pairs
2. Out-of-core sort those pairs by key (in a temp partition)
3. Feed the sorted iterator to `BPlusTree.bulkLoad()`

The heap file is never reordered — that's only for a **clustered index**, which RookieDB doesn't implement. Here the index is always unclustered; the heap file stays in insertion order.

On the iterator being large: yes, the sorted pairs can span many pages. That's exactly why `bulkLoad()` takes an `Iterator` (lazy) rather than a `List` — it processes one pair at a time without materializing everything in RAM. The out-of-core sort handles the heavy lifting before `bulkLoad()` is called.

Full picture:
```
heap file (unordered)
  → scan → (key, RecordId) pairs
  → out-of-core sort
  → sorted iterator
  → BPlusTree.bulkLoad()
```

---

**Q: But if we want to implement a clustered index, we need to use out-of-core sort on the whole heap file, do batch and streaming to get an ordered heap file?**

Yes, exactly. For a clustered index the process would be:

1. Out-of-core sort the **entire heap file** by the index key — rewriting the data pages in sorted order
2. Then bulkLoad the index from that sorted heap file (scan in order → `(key, RecordId)` pairs are already sorted)

The tricky part is that sorting the heap file **changes all the RecordIds** (records move to different pages/slots), so:
- Any existing indexes on that table would have stale `RecordId`s and need to be rebuilt
- This is why clustered indexes are typically built once upfront (before any other indexes exist), not retrofitted

This is also why most databases only allow **one** clustered index per table — the physical ordering of the heap file can only match one key order at a time.

---

**Q: Is steps 1-3 (heap scan → sort → bulkLoad) what this codebase does for the whole bulkLoad()? With B buffer pages in RAM for Pass 1 of out-of-core sort, what are the steps?**

This codebase's `bulkLoad()` only handles step 3 — the B+ tree construction from a pre-sorted iterator. Steps 1 and 2 (the out-of-core sort) are done elsewhere in the query layer before `bulkLoad()` is ever called.

For the out-of-core sort with B buffer pages:

**Pass 0 (generate sorted runs):**
- Read B pages at a time from the `(key, RecordId)` pairs
- Sort each B-page chunk in RAM
- Write each sorted chunk (a "run") back to disk
- Result: `ceil(N/B)` sorted runs, each B pages long

**Pass 1+ (merge):**
- Each merge pass takes B-1 runs as input (one buffer page per run) + 1 output buffer page
- Merge B-1 runs at a time → one longer sorted run
- Repeat until 1 sorted run remains

**Total passes:** `1 + ceil(log_{B-1}(ceil(N/B)))`

For the common case where `ceil(N/B) <= B-1` (i.e., `N <= B*(B-1)`), you only need **2 passes**.

---

**Q: Point to the modules that implement Pass 0 and Pass 1+, and why can I directly choose B pages from (key, RecordId) pairs when I initially only have the heap file with actual records?**

- **Pass 0 + Pass 1+**: `query/SortOperator.java` — implements the full out-of-core sort
- **`query/disk/Run.java`** — represents one sorted run on disk
- **`query/disk/Partition.java`** — underlying disk buffer partition for a run

For your second question — you're right that you don't start with `(key, RecordId)` pairs directly. The actual flow is:

1. **Scan heap file** → full records come out (`SequentialScanOperator`)
2. **Project** → extract just `(keyColumn, RecordId)` from each record — now the tuples are tiny
3. **Pass 0 of sort** → load B pages of these small pairs into RAM, sort, write a run
4. **Pass 1+** → merge runs
5. **bulkLoad()** → feed sorted iterator to B+ tree

So Pass 0 reads B pages worth of the **projected pairs**, not B pages of the original heap file. Since `(key, RecordId)` is much smaller than a full record, B buffer pages can hold far more pairs — which means fewer runs and potentially fewer merge passes.

---

**Q: The course says total I/O cost is 2*N*passes, where one I/O = read/write one page. This formula is correct, but in reality we fetch B pages at a time (sequential). But if we fetch one page at a time, 0, 1, ..., B-1, is that still sequential access?**

Yes, fetching pages 0, 1, ..., B-1 one at a time **is still sequential access** — the key factor is **disk head movement**, not how many pages you request per OS call.

Sequential access means the disk head moves in one direction without seeking back. Whether your software fetches them one page at a time or in a batch, the disk reads them in order from the same track/cylinder — no seek penalty between consecutive pages.

- **Batch (B pages)**: one syscall, OS reads a contiguous block — best case
- **One page at a time, sequential**: B syscalls, but disk head never seeks back — still sequential, just more syscall overhead

The expensive case is **random access** — jumping to page 0, then page 500, then page 3 — because each jump requires a disk seek (the head physically moves), which is ~1000x slower than sequential reads.

---

**Q: But disk has rotational delay.**

Good point. Even for sequential pages, there are two components of disk access latency:

- **Seek time**: head moves to the right track — ~0 for sequential (next page is on same/adjacent track)
- **Rotational delay**: waiting for the disk to rotate to the right sector — this hits **every page** regardless of sequential vs random

So fetching pages 0, 1, ..., B-1 one at a time still pays rotational delay B times, while fetching them as one contiguous block pays it **once** (then the rest stream off the spinning disk).

In practice this is why:
- DBs use **large I/O units** (e.g. 4KB → 64KB or larger per fetch) to amortize rotational delay
- The `2*N*passes` formula **abstracts this away** — it counts logical page I/Os, not physical latency. The constant factor (how long one "I/O" takes) depends on access pattern and hardware
- On **SSDs**, rotational delay doesn't exist, so one-page-at-a-time vs batch matters much less — seek and rotational latency are both near zero

So the formula is correct as a cost model, but the real-world constant behind "one I/O" is smaller when you batch sequential reads.
