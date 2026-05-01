# Recovery Module: ARIES Recovery System

## Architecture Overview

The recovery module implements the **ARIES** (Algorithm for Recovery and Isolation Exploiting Semantics) protocol. The architecture has three layers:

```
RecoveryManager interface
    |
    +-- ARIESRecoveryManager  (real implementation)
    +-- DummyRecoveryManager  (no-op for testing)

LogManager  (appends/reads/flushes log records on partition 0)
    |
LogRecord (abstract) --> 16 concrete record types in records/
```

The recovery manager is tightly coupled with the buffer manager: `BufferManager` calls `pageFlushHook()` before flushing any dirty page, and `logPageWrite()` on every page modification. The `LogManager` itself uses the buffer manager to read and write log pages.

---

## RecoveryManager Interface

Defines the full lifecycle of recovery operations:

| Category | Methods |
|----------|---------|
| Setup | `initialize()`, `setManagers(dsm, bm)` |
| Transaction lifecycle | `startTransaction()`, `commit()`, `abort()`, `end()` |
| Page/partition logging | `logPageWrite()`, `logAllocPage()`, `logFreePage()`, `logAllocPart()`, `logFreePart()` |
| Savepoints | `savepoint()`, `releaseSavepoint()`, `rollbackToSavepoint()` |
| Buffer integration | `pageFlushHook()`, `diskIOHook()`, `dirtyPage()` |
| Checkpointing | `checkpoint()`, `flushToLSN()` |
| Recovery | `restart()` |

### Cyclic dependency: setManagers()

The buffer manager needs the recovery manager (to call `pageFlushHook` on eviction and `logPageWrite` on modification), and the recovery manager needs the buffer manager (to write log records and redo changes). This cycle is broken by a two-phase initialization: the recovery manager is constructed first, then `setManagers()` injects the buffer and disk space managers.

---

## ARIESRecoveryManager.java -- The Real Implementation

### Core data structures

```java
Map<Long, Long> dirtyPageTable;              // pageNum -> recLSN (first LSN that dirtied the page)
Map<Long, TransactionTableEntry> transactionTable;  // transNum -> entry (lastLSN, status, savepoints)
LogManager logManager;
boolean redoComplete;                        // guards DPT cleanup during redo
```

### Forward processing (normal operation)

**`startTransaction(transaction)`** -- adds the transaction to `transactionTable`.

**`commit(transNum)`** (TODO) -- appends a `CommitTransactionLogRecord`, flushes the log to the commit LSN (the "force" in force-at-commit), and updates the transaction's status to COMMITTING.

**`abort(transNum)`** (TODO) -- appends an `AbortTransactionLogRecord` and updates status to RECOVERY_ABORTING. Does **not** perform rollback yet.

**`end(transNum)`** (TODO) -- if the transaction was aborting, rolls back all changes via `rollbackToLSN(transNum, 0)`. Then appends an `EndTransactionLogRecord` and removes the transaction from the table.

**`logPageWrite(transNum, pageNum, offset, before, after)`** (TODO) -- appends an `UpdatePageLogRecord` with the before/after images, updates `lastLSN` in the transaction table, and adds the page to the dirty page table (with `recLSN` = this LSN if not already present).

**`logAllocPart/logFreePart/logAllocPage/logFreePage`** -- these are already implemented. Each appends the appropriate record, updates `lastLSN`, and **flushes the log** immediately because the disk space change is visible as soon as the method returns. Partition 0 (the log partition) is always excluded. `logFreePage` also removes the page from the dirty page table.

### The rollback helper: `rollbackToLSN(transNum, LSN)`

Walks backward through the transaction's log chain (following `prevLSN` pointers), undoing each undoable record:

1. Start at the transaction's `lastLSN`. If it is a CLR, jump to `undoNextLSN` to skip already-undone records.
2. While the current LSN > target LSN:
   - If the record is undoable: call `record.undo(lastLSN)` to create a CLR, append it, then call `clr.redo()` to physically apply the undo.
   - Advance to the next record via `undoNextLSN` (for CLRs) or `prevLSN` (for regular records).

Note: `record.undo()` does **not** execute the undo -- it just creates the compensation log record. The actual undo is performed by `clr.redo()`.

### Savepoints

Savepoints record the transaction's `lastLSN` at the time of creation. `rollbackToSavepoint` calls `rollbackToLSN(transNum, savepointLSN)` to undo everything after the savepoint.

### Checkpointing

`checkpoint()` implements fuzzy checkpointing:

1. Appends a `BeginCheckpointLogRecord`.
2. Iterates the dirty page table and transaction table, filling `EndCheckpointLogRecord`s. Uses `EndCheckpointLogRecord.fitsInOneRecord()` to split across multiple end-checkpoint records when the tables are too large for one page.
3. Flushes the log through the last end-checkpoint record.
4. Rewrites the master record (LSN 0) to point to the begin-checkpoint LSN.

### Buffer manager hooks

**`pageFlushHook(pageLSN)`** -- called by `BufferManager` before any dirty data page is flushed to disk. Calls `logManager.flushToLSN(pageLSN)` to ensure the WAL protocol: the log record that describes a page modification must be on disk **before** the modified page itself.

**`diskIOHook(pageNum)`** -- called after a page has been written to disk. Removes the page from the dirty page table (only after redo is complete, guarded by the `redoComplete` flag).

**`dirtyPage(pageNum, LSN)`** -- adds a page to the DPT with `recLSN = min(existing, new)`. Uses `putIfAbsent` + `computeIfPresent` to handle race conditions where a later log record's insert beats an earlier one.

---

## The Three Recovery Phases

Invoked by `restart()`:

```java
restartAnalysis();
restartRedo();
redoComplete = true;
cleanDPT();       // remove pages that are actually clean in the buffer manager
restartUndo();
checkpoint();     // take a checkpoint to speed up future recovery
```

### Phase 1: Analysis (`restartAnalysis()`)

Reconstructs the transaction table and dirty page table as of the crash.

1. Read the master record (LSN 0) to find the last successful checkpoint's begin-LSN.
2. Scan forward from that checkpoint:
   - **Transaction operations** (`getTransNum()` present): add transaction to table if missing, update `lastLSN`.
   - **Page-related records** (`getPageNum()` present): update/undo-update dirtied the page (add to DPT with `recLSN`); free/undo-alloc flushed to disk (remove from DPT).
   - **Status changes**: commit -> COMMITTING, abort -> RECOVERY_ABORTING, end -> remove from table.
   - **End-checkpoint records**: merge DPT entries (replace existing), merge transaction table entries (keep the more advanced status, take the larger `lastLSN`).
3. After scanning all records:
   - COMMITTING transactions: clean up, write end record, remove from table.
   - RUNNING transactions: change to RECOVERY_ABORTING, write abort record.
   - RECOVERY_ABORTING transactions: leave as-is (will be handled by undo).

### Phase 2: Redo (`restartRedo()`)

Repeats history to restore the database to its exact pre-crash state.

1. Find the starting point: the **minimum `recLSN`** across all entries in the dirty page table.
2. Scan forward from that LSN. For each redoable record:
   - **Partition-related** (Alloc/Free/UndoAlloc/UndoFree Part): always redo.
   - **Page allocation** (AllocPage/UndoFreePage): always redo.
   - **Page modification** (UpdatePage/UndoUpdatePage/FreePage/UndoAllocPage): redo only if:
     - The page is in the DPT, **and**
     - The record's LSN >= the page's `recLSN` in the DPT, **and**
     - The page's on-disk `pageLSN` < the record's LSN.

The third check (fetching the page and comparing `pageLSN`) avoids redundant redos for pages that were already flushed before the crash.

### Phase 3: Undo (`restartUndo()`)

Rolls back all incomplete transactions (those in RECOVERY_ABORTING status).

1. Build a max-priority queue of `(lastLSN, transNum)` for all aborting transactions.
2. Repeatedly pop the largest LSN:
   - If the record is undoable: create a CLR via `record.undo()`, append it, redo it.
   - Advance to `undoNextLSN` (if the record is a CLR) or `prevLSN` (otherwise).
   - If the next LSN is 0, the transaction is fully undone: clean up, write end record, remove from table.

Using a priority queue across all aborting transactions avoids re-reading log pages -- the undo processes records in reverse LSN order globally rather than per-transaction.

---

## LogManager.java -- Log Storage

### LSN scheme

The log lives on partition 0. LSNs encode both the page number and offset within the page:

```
LSN = pageNum * 10000 + offsetWithinPage
```

This allows up to 10,000 log entries per page. Examples:
- Page 1: LSNs 10000, 10040, 10080, ...
- Page 2: LSNs 20000, 20030, 20055, ...

LSN 0 is always the master record (on page 0).

### Key operations

- `appendToLog(record)` -- writes to the current log tail page; allocates a new page when full. Returns the new LSN. Log pages are not flushed immediately (steal/no-force for data pages, force-at-commit for the log).
- `fetchLogRecord(LSN)` -- reads a record at a specific LSN by computing its page and offset.
- `flushToLSN(LSN)` -- flushes all unflushed log pages up to and including the page containing the given LSN.
- `rewriteMasterRecord(record)` -- the master record is the **only** log record that can be overwritten. Updated during checkpointing to point to the latest begin-checkpoint LSN.
- `scanFrom(LSN)` -- returns a forward iterator over log records starting at the given LSN.

### Unflushed tail tracking

The `unflushedLogTail` deque tracks log pages that have been written in memory but not yet flushed to disk. `flushToLSN` iterates this deque, flushing and removing pages up to the target.

---

## LogRecord.java -- Base Class

Abstract base with common fields:
- `LSN` -- set by `LogManager` after appending (not stored on disk).
- `type` -- `LogType` enum value.

Subclass-dependent optional accessors (return `Optional.empty()` by default):
- `getTransNum()`, `getPrevLSN()`, `getUndoNextLSN()` -- for transaction-linked records.
- `getPageNum()`, `getPartNum()` -- for page/partition-related records.
- `getDirtyPageTable()`, `getTransactionTable()` -- for end-checkpoint records.

Behavioral methods:
- `isUndoable()` / `isRedoable()` -- whether the record supports undo/redo.
- `undo(lastLSN)` -- creates a CLR (compensation log record) but does not execute it. Only valid for undoable records.
- `redo(rm, dsm, bm)` -- physically applies the change. Calls the `onRedo` hook (used for testing).

### Serialization

`toBytes()` serializes the record with a 1-byte type tag followed by type-specific data. `fromBytes(Buffer)` dispatches on the type tag to the appropriate subclass's `fromBytes`.

---

## LogType.java -- Record Type Enum

16 record types organized into categories:

| Category | Types |
|----------|-------|
| Meta | `MASTER` |
| Page operations | `ALLOC_PAGE`, `UPDATE_PAGE`, `FREE_PAGE` |
| Partition operations | `ALLOC_PART`, `FREE_PART` |
| Transaction status | `COMMIT_TRANSACTION`, `ABORT_TRANSACTION`, `END_TRANSACTION` |
| Checkpointing | `BEGIN_CHECKPOINT`, `END_CHECKPOINT` |
| CLRs (compensation) | `UNDO_ALLOC_PAGE`, `UNDO_UPDATE_PAGE`, `UNDO_FREE_PAGE`, `UNDO_ALLOC_PART`, `UNDO_FREE_PART` |

Values are 1-indexed (ordinal + 1) to reserve 0 as a "no record" marker in serialized form.

---

## TransactionTableEntry.java

Tracks per-transaction recovery state:

```java
Transaction transaction;          // the Transaction object
long lastLSN = 0;                // LSN of the most recent log record for this transaction
Map<String, Long> savepoints;    // name -> LSN at time of savepoint creation
```

`lastLSN` is the critical field -- it is the starting point for the `prevLSN` chain that links all of a transaction's log records backward. Every time a new log record is appended for a transaction, `lastLSN` must be updated.

---

## Log Record Types (records/ subdirectory)

### Forward operation records (undoable + redoable)

| Record | Fields | Undo produces | Redo action |
|--------|--------|---------------|-------------|
| `UpdatePageLogRecord` | transNum, pageNum, prevLSN, offset, before[], after[] | `UndoUpdatePageLogRecord` | Writes `after` bytes to page at offset |
| `AllocPageLogRecord` | transNum, pageNum, prevLSN | `UndoAllocPageLogRecord` | Allocates the page |
| `FreePageLogRecord` | transNum, pageNum, prevLSN | `UndoFreePageLogRecord` | Frees the page |
| `AllocPartLogRecord` | transNum, partNum, prevLSN | `UndoAllocPartLogRecord` | Allocates the partition |
| `FreePartLogRecord` | transNum, partNum, prevLSN | `UndoFreePartLogRecord` | Frees the partition |

### CLR records (redoable only, not undoable)

| Record | Extra field | Redo action |
|--------|-------------|-------------|
| `UndoUpdatePageLogRecord` | undoNextLSN, offset, after[] | Writes `after` bytes (the old data) to page at offset; calls `dirtyPage()` |
| `UndoAllocPageLogRecord` | undoNextLSN | Frees the page (flushes log first) |
| `UndoFreePageLogRecord` | undoNextLSN | Allocates the page (flushes log first) |
| `UndoAllocPartLogRecord` | undoNextLSN | Frees the partition (flushes log first) |
| `UndoFreePartLogRecord` | undoNextLSN | Allocates the partition (flushes log first) |

CLRs are **never undone** (`isUndoable()` returns false). They have an `undoNextLSN` field that points to the **next record to undo**, allowing the undo process to skip the record that was just compensated. This is what makes ARIES restart-safe: if a crash occurs during undo, the CLRs ensure no work is repeated.

### Transaction status records (neither undoable nor redoable)

| Record | Fields |
|--------|--------|
| `CommitTransactionLogRecord` | transNum, prevLSN |
| `AbortTransactionLogRecord` | transNum, prevLSN |
| `EndTransactionLogRecord` | transNum, prevLSN |

These mark state transitions but do not modify data, so they have no redo/undo behavior.

### Checkpoint records

| Record | Fields |
|--------|--------|
| `BeginCheckpointLogRecord` | (none -- just a marker) |
| `EndCheckpointLogRecord` | dirtyPageTable (Map<Long,Long>), transactionTable (Map<Long, Pair<Status,Long>>) |

`EndCheckpointLogRecord.fitsInOneRecord(dptSize, txnSize)` checks whether the combined DPT and transaction table data fits in one page (DPT entries are 16 bytes each, txn entries are 17 bytes each, plus 5 bytes of header).

### Master record

| Record | Fields |
|--------|--------|
| `MasterLogRecord` | lastCheckpointLSN |

Always lives at LSN 0. Rewritten (not appended) on each checkpoint.

---

## The Write-Ahead Logging (WAL) Protocol

The WAL guarantee is: **the log record describing a modification must be on stable storage before the modified page is written to disk.**

In RookieDB this is enforced in `BufferManager`'s page flush path:

```java
// BufferManager.java, inside the page frame's flush() method:
if (!this.logPage) {
    recoveryManager.pageFlushHook(this.getPageLSN());
}
BufferManager.this.diskSpaceManager.writePage(pageNum, contents);
```

`pageFlushHook` calls `logManager.flushToLSN(pageLSN)`, ensuring that all log records up to and including the page's `pageLSN` are flushed before the dirty page hits disk. The `!this.logPage` guard avoids infinite recursion (log pages are never WAL-protected by themselves).

Additionally, operations that immediately modify the disk layout (alloc/free of pages and partitions) flush the log synchronously in the recovery manager methods (`logAllocPart`, `logFreePart`, `logAllocPage`, `logFreePage`) by calling `logManager.flushToLSN(LSN)` right after appending the record.

---

## DummyRecoveryManager.java

A no-op implementation used before Project 5 is implemented. Key behaviors:
- `commit()` / `end()` set transaction status directly.
- `abort()`, `savepoint()`, `rollbackToSavepoint()`, and `checkpoint()` throw `UnsupportedOperationException` since they require real recovery logic.
- All logging methods return 0L and do nothing.
- `restart()` and `close()` are no-ops.

---

## Cross-Module Relationships

### Recovery <-> Buffer Manager

The cyclic dependency is the central architectural constraint. The buffer manager calls into recovery (`pageFlushHook`, `logPageWrite`, `diskIOHook`), and recovery calls into the buffer manager (`logManager` writes to log pages, `redo()` fetches data pages). The `setManagers()` two-phase init breaks the cycle.

### Recovery <-> Concurrency

Log records and the recovery manager use `DummyLockContext` when accessing pages during redo/undo. This bypasses the locking system because recovery runs single-threaded at startup before any transactions begin.

### The prevLSN Chain

Every transaction-linked log record stores `prevLSN`, forming a backward-linked list per transaction. This chain is how `rollbackToLSN` and `restartUndo` walk backward through a transaction's history without scanning the entire log. CLR records have an additional `undoNextLSN` pointer that allows skipping over already-undone records, providing O(n) undo even with crashes during recovery.
