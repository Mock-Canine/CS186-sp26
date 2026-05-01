# Table and Record Storage Layer

This module implements the heap file storage engine for RookieDB. It is responsible for persisting records to disk, managing free space across pages, and maintaining statistics for query optimization.

## Architecture Overview

```
User code
   |
   v
 Table            -- public API: addRecord / getRecord / updateRecord / deleteRecord
   |
   v
 PageDirectory    -- header pages tracking which data pages have free space
   |
   v
 BufferManager    -- (external) page-level I/O
```

A `Table` does not manage its own pages directly. Instead it delegates all page allocation and free-space tracking to a `PageDirectory`, which in turn talks to the `BufferManager` and `DiskSpaceManager`.

---

## Record.java

A `Record` is an immutable ordered list of `DataBox` values -- one value per column. It carries no schema information itself; the schema is always supplied externally when serialization is needed.

Key points:

- `toBytes(Schema)` serializes by concatenating each `DataBox.toBytes()` in field order. The total size equals `schema.getSizeInBytes()`.
- `fromBytes(Buffer, Schema)` deserializes by reading each field type from the buffer in order.
- `concat(Record other)` is used by join operators to build combined rows.
- The varargs constructor `Record(Object...)` calls `DataBox.fromObject` for convenience.

## RecordId.java

A `RecordId` is a `(long pageNum, short entryNum)` pair -- a physical pointer to a record's location.

- `pageNum` identifies the data page.
- `entryNum` is the slot index within that page's bitmap.
- Serialized as exactly **10 bytes** (8-byte long + 2-byte short).
- Implements `Comparable<RecordId>`, ordering by page number then entry number. This ordering is used when iterating over a table in physical order.

## Schema.java

A `Schema` is an ordered list of `(fieldName, Type)` pairs. It defines the structure of records in a table.

Key behaviors:

- **Builder pattern**: `new Schema().add("x", Type.intType()).add("y", Type.floatType())` -- `add()` returns `this` for chaining.
- **`getSizeInBytes()`**: returns the fixed byte width of a record conforming to this schema. This is the sum of each field type's byte size and is used throughout the storage layer to compute page layout.
- **`verify(Record)`**: validates a record against the schema and performs implicit casts:
  - Strings of the wrong length are resized to the expected `Type.getSizeInBytes()`.
  - Ints are promoted to Floats when the schema expects a float.
  - Throws `DatabaseException` on type mismatch.
- **`findField(String)`**: locates a column by name. Supports both qualified (`table.col`) and unqualified (`col`) names. Throws if the name is ambiguous or unknown.
- **`concat(Schema)`**: concatenates two schemas, used when computing join output schemas.
- **Serialization** (`toBytes` / `fromBytes`): writes `[numFields, (nameLen, name, typeBytes)*]`. This is stored in the `_metadata.tables` system table, not in the heap file itself.

## PageDirectory.java

`PageDirectory` is the heap file implementation. It manages a linked list of **header pages**, each of which tracks a set of **data pages** and their free space.

### Header Page Layout

```
Byte 0:       0x01 (validity marker)
Bytes 1-4:    page directory ID (random int, for corruption detection)
Bytes 5-12:   page number of next header page (-1 if none)
Bytes 13+:    array of DataPageEntry (10 bytes each)
```

Each `DataPageEntry` is:
```
Bytes 0-7:   page number of data page (-1 if slot unused)
Bytes 8-9:   free space in bytes on that data page
```

The number of entries per header page is `(EFFECTIVE_PAGE_SIZE - 13) / 10`.

### Data Page Layout

Each data page has a 10-byte header:
```
Bytes 0-3:   page directory ID (for validation)
Bytes 4-7:   index of which header page manages this data page
Bytes 8-9:   slot index within that header page
```

This header allows `updateFreeSpace` to jump directly from a data page back to its managing header page entry, without scanning. The `DataPage` wrapper class transparently skips this header -- callers (i.e., `Table`) see an effective page size of `BufferManager.EFFECTIVE_PAGE_SIZE - 10`.

### Key operations

- **`getPageWithSpace(short requiredSpace)`**: walks the header page linked list looking for a data page with enough free space. If no existing page qualifies, allocates a new data page from the `BufferManager` and initializes its header. If the current header page is full of entries, creates a new header page first.
- **`updateFreeSpace(Page, short newFreeSpace)`**: called by `Table.deleteRecord` to report freed space. If the entire page becomes free, the page is deallocated via `bufferManager.freePage()`.
- **`iterator()`**: returns a `BacktrackingIterator<Page>` that walks all header pages and yields every valid data page.

### Concurrency note

Header pages are **not locked** for the full transaction. Only the buffer frame pin lock is held during header page reads/writes. This sacrifices strict isolation on header metadata (a transaction might be told to use a different data page than expected), but avoids contention on the page directory.

---

## Table.java

`Table` is the public-facing API. It layers record-level operations on top of `PageDirectory`'s page-level storage.

### Data page format (managed by Table)

Each data page (after the PageDirectory header is stripped) contains:

```
[bitmap: n bytes] [record 0] [record 1] ... [record m-1]
```

- The **bitmap** has one bit per record slot. Bit = 1 means the slot is occupied.
- `n` and `m` are computed by `computeNumRecordsPerPage` to maximize records per page. The formula accounts for the fact that each record costs `(8 * schemaSize + 1)` bits (the record bytes plus one bitmap bit).
- **Special case**: if only one record fits per page, the bitmap is omitted (`bitmapSizeInBytes = 0`) and the entire page is a single record. An unoccupied page is simply freed.

### Full page records mode

`setFullPageRecords()` forces `numRecordsPerPage = 1` and `bitmapSizeInBytes = 0`, even for small records. This is used when tuple-level locking is needed (since the database only supports page-level locks, giving each record its own page approximates tuple-level locking at the cost of space).

### Insert flow

```
addRecord(Record record)
  1. schema.verify(record)          -- validate types, implicit casts
  2. pageDirectory.getPageWithSpace(schemaSize)  -- find/allocate a page
  3. getBitMap(page)                 -- read the bitmap
  4. scan bitmap for first ZERO bit  -- find free slot (entryNum)
  5. insertRecord(page, entryNum, record)  -- write bytes at offset
  6. set bitmap bit to ONE, write bitmap back
  7. stats.addRecord(record)         -- update statistics
  8. return RecordId(pageNum, entryNum)
```

### Get flow

```
getRecord(RecordId rid)
  1. validateRecordId(rid)           -- entryNum in [0, numRecordsPerPage)
  2. fetchPage(rid.pageNum)          -- pin page via PageDirectory
  3. check bitmap bit -- if ZERO, throw "record does not exist"
  4. compute offset = bitmapSizeInBytes + entryNum * schemaSize
  5. Record.fromBytes(buffer at offset, schema)
  6. unpin page
```

### Delete flow

```
deleteRecord(RecordId rid)
  1. getRecord(rid)                  -- read old record (for stats + return)
  2. set bitmap bit to ZERO
  3. stats.removeRecord(oldRecord)
  4. pageDirectory.updateFreeSpace(page, newFreeSpace)
     -- if page is fully empty, PageDirectory frees it
  5. return old record
```

### Iterators

Table provides three levels of iteration:

1. **`ridIterator()`** -- returns `BacktrackingIterator<RecordId>`. Walks every data page via `pageDirectory.iterator()`, then for each page yields every RecordId whose bitmap bit is set. Uses `RIDPageIterator` (one page at a time) concatenated via `ConcatBacktrackingIterator`.

2. **`recordIterator(Iterator<RecordId>)`** -- wraps a RecordId iterator, calling `getRecord()` for each. Supports backtracking if the underlying RecordId iterator does.

3. **`iterator()`** -- convenience: `recordIterator(ridIterator())`, a full table scan yielding `Record` objects.

All iterators support the **backtracking** protocol (`markPrev`, `markNext`, `reset`), which is critical for nested loop joins.

---

## Statistics: `table/stats/`

### TableStats.java

Maintains per-table statistics used for cost estimation during query optimization:

- `numRecords` -- updated incrementally on `addRecord` / `removeRecord`.
- `numRecordsPerPage` -- set at construction time from the schema.
- `getNumPages()` -- derived: `ceil(numRecords / numRecordsPerPage)`.
- `histograms` -- one `Histogram` per column.

**`refreshHistograms(int buckets, Table table)`**: rebuilds all histograms by doing a full table scan. This is the only time histograms are populated with real data; the incremental `addRecord`/`removeRecord` methods only update `numRecords`, not histograms.

**`copyWithPredicate(column, predicate, value)`**: estimates the statistics after applying a filter. Computes a reduction factor from the target column's histogram, then uniformly reduces all other column histograms by that factor (assuming column independence).

**`copyWithJoin(leftIndex, rightStats, rightIndex)`**: estimates post-join statistics. Uses the standard formula: `outputSize = |L| * |R| / max(V(L, joinCol), V(R, joinCol))`, where `V` is the number of distinct values.

### Histogram.java

A fixed-width equi-width histogram over quantized float values.

- All data types are mapped to floats via `quantization()`: booleans become 0/1, ints and floats are direct, strings use `hashCode()`.
- **`buildHistogram(Table, attribute)`**: two-pass algorithm. Pass 1 finds min/max. Pass 2 distributes values into buckets.
- **`filter(predicate, value)`**: returns a `float[]` mask (one entry per bucket, each in [0, 1]) that estimates what fraction of each bucket survives the predicate. Used for selectivity estimation.
- **`computeReductionFactor(predicate, value)`**: summarizes the mask into a single scalar, weighted by distinct counts.

### Bucket.java

A single histogram bucket with `[start, end)` range, a count, and a distinct count. During construction, a `HashSet<Float>` dictionary tracks distinct values; after `setDistinctCount()` is called, the dictionary is discarded.

---

## Cross-file Relationships

- `Table` depends on `Schema` for record size computation and on `PageDirectory` for all I/O.
- `Schema.verify()` is called on every insert and update to ensure type safety before bytes hit disk.
- `RecordId` is the bridge between the table layer and upper layers (indices, query operators). It is 10 bytes to match the B+ tree leaf entry format.
- `PageDirectory` is reusable -- it does not know about records or schemas. `Table` tells it `emptyPageMetadataSize` so it can accurately track free space.
- `TableStats` feeds into the query optimizer (`QueryPlan.minCostSingleAccess`, `minCostJoinType`) to drive cost-based plan selection.
- The `TODO(proj4_part2)` markers in `Table` and `PageDirectory` indicate where multigranularity locking will be implemented; currently all lock checks are `LockType.NL` (no lock).
