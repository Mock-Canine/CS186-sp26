# io/ Module -- Disk Space Management

## Overview

This module manages **persistent storage**: mapping logical (virtual) page numbers to physical bytes on disk. It has three layers: a public interface (`DiskSpaceManager`), a coordinating implementation (`DiskSpaceManagerImpl`), and per-partition file handles (`PartitionHandle`).

---

## Virtual Page Numbers

A virtual page number is a `long` that encodes **both** the partition and the page within that partition using decimal arithmetic (not bit-shifting):

```
virtualPageNum = partNum * 10_000_000_000L + pageNum
```

This means partition 1, page 6 is `10000000006` -- human-readable in a debugger. The static helpers on `DiskSpaceManager` perform the encode/decode:

```java
static int  getPartNum(long page)                    // page / 10^10
static int  getPageNum(long page)                    // page % 10^10
static long getVirtualPageNum(int partNum, int pageNum)  // partNum * 10^10 + pageNum
```

`INVALID_PAGE_NUM` is -1, which can never be produced by this encoding.

---

## DiskSpaceManager Interface

Defines the contract consumed by the rest of the database (in particular the `BufferManager`):

| Method | Purpose |
|---|---|
| `allocPart()` / `allocPart(int)` | Create a new partition (OS file) |
| `freePart(int)` | Delete a partition |
| `allocPage(int partNum)` | Allocate the next free page in a partition |
| `allocPage(long pageNum)` | Allocate a *specific* virtual page (used during recovery) |
| `freePage(long)` | Deallocate a page |
| `readPage(long, byte[])` / `writePage(long, byte[])` | Transfer exactly `PAGE_SIZE` (4096) bytes |
| `pageAllocated(long)` | Allocation check |

All byte buffers passed to read/write must be exactly `PAGE_SIZE` bytes.

---

## DiskSpaceManagerImpl -- The Coordinator

### Partition-to-File Mapping

Each partition is a single OS file named by its partition number, stored under `dbDir`. On construction, the manager scans the directory, opens every existing file as a `PartitionHandle`, and sets `partNumCounter` to one past the highest file name found.

### Locking Protocol

Two-level locking prevents deadlocks between partitions:

1. **`managerLock`** (ReentrantLock) -- acquired first, held briefly to look up the `PartitionHandle` in the `partInfo` HashMap and grab its lock.
2. **`partitionLock`** (per-partition ReentrantLock inside `PartitionHandle`) -- acquired while `managerLock` is still held, then `managerLock` is released before the actual I/O.

Every public method follows this acquire-manager-lock, lookup, acquire-partition-lock, release-manager-lock, do-work, release-partition-lock pattern. This allows concurrent I/O on different partitions.

### Recovery Integration

`DiskSpaceManagerImpl` calls the `RecoveryManager` to log partition and page allocation/free operations. The log call happens *before* the file is created (for `allocPart`) and *after* pages are freed (for `freePart`), following WAL protocol.

### Key Constants

```java
MAX_HEADER_PAGES   = PAGE_SIZE / 2 = 2048       // master page stores 2 bytes per header
DATA_PAGES_PER_HEADER = PAGE_SIZE * 8 = 32768   // header page is a bitmap, 1 bit per page
```

Maximum data pages per partition = 2048 * 32768 = 67,108,864 (64M pages = 256 GB at 4 KB/page).

---

## PartitionHandle -- The File Manager

Each `PartitionHandle` wraps one `RandomAccessFile` / `FileChannel` and manages the two-level page directory inside that file.

### File Layout

```
Offset (pages):  0    1    2    3    ... 32769   32770   32771 ...
Page Type:      [M]  [H0] [D0] [D1] ... [D32767] [H1]   [D32768] ...
```

- **Master page** (page 0): array of 2048 unsigned 16-bit integers. `masterPage[i]` = count of allocated data pages under header page `i`. Stored as `short` on disk, widened to `int` in memory to handle unsigned range.
- **Header page `i`**: 4096-byte bitmap. Bit `j` = 1 means data page `j` under this header is allocated. Each header manages 32,768 data pages.
- **Data pages**: the actual 4096-byte pages that the rest of the database reads/writes.

### Offset Calculations

```java
masterPageOffset()             = 0
headerPageOffset(headerIndex)  = (1 + headerIndex * (DATA_PAGES_PER_HEADER + 1)) * PAGE_SIZE
dataPageOffset(pageNum)        = (2 + pageNum/DATA_PAGES_PER_HEADER + pageNum) * PAGE_SIZE
```

The `+1` inside `headerPageOffset` accounts for the header page itself occupying one slot among the data pages it manages. The `dataPageOffset` formula adds: 1 (master) + 1 (first header) + number of additional headers that precede this page + the page's own index.

### Page Allocation Flow

1. **`allocPage()` (no arguments)**: scan `masterPage[]` for the first header with a count < 32768. Within that header's bitmap, find the first zero bit. Call `allocPage(headerIndex, pageIndex)`.
2. **`allocPage(headerIndex, pageIndex)`**: set the bit to 1, recount bits to update `masterPage[headerIndex]`, log via `RecoveryManager`, then **immediately flush** both master page and header page to disk. Return `pageNum = pageIndex + headerIndex * DATA_PAGES_PER_HEADER`.
3. Back in `DiskSpaceManagerImpl.allocPage`, the newly allocated page is zeroed out (`writePage(pageNum, new byte[PAGE_SIZE])`), and the virtual page number is returned.

### Page Deallocation Flow

1. Verify the bit is set (page is allocated).
2. If a transaction is active, read the current page contents and log two half-page write records (splitting at `RESERVED_SPACE + EFFECTIVE_PAGE_SIZE/2`), then log the free. This ensures undo can restore the page data.
3. Clear the bit, recount, flush master + header.

The split into two log records exists because each log record stores before/after images, and a full-page redo/undo pair would be 2 * 4060 = 8120 bytes -- exceeding the maximum log record size. Splitting at the halfway point keeps each record under the limit.

### Master and Header Caching

Master and header pages are **always in memory** (stored in `int[] masterPage` and `byte[][] headerPages`). They are loaded from disk on `open()` and flushed synchronously after every modification. This means allocation metadata never needs to go through the `BufferManager` -- it is managed entirely within this module.

---

## PageException

A simple `RuntimeException` subclass. Thrown for all I/O-level errors: unallocated page access, file creation failures, read/write errors. The rest of the database catches this as an unchecked exception.

---

## Cross-Module Boundary

The `BufferManager` is the sole consumer of `DiskSpaceManager.readPage` / `writePage`. Higher layers never call `DiskSpaceManager` directly for data access -- they go through `BufferManager.fetchPage`, which loads a page into a cached frame and returns a `Page` handle. The disk space manager's constants `PAGE_SIZE` and `INVALID_PAGE_NUM` are referenced throughout the codebase; `RESERVED_SPACE` and `EFFECTIVE_PAGE_SIZE` from `BufferManager` are even referenced inside `PartitionHandle.freePage` to split the undo log record correctly, creating a tight coupling between these two modules.
