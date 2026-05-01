# Top-Level Classes in `edu.berkeley.cs186.database`

This document explains the five files directly inside the `database/` package
(not the subpackages like `index/`, `query/`, etc.). These classes form the
outermost layer of RookieDB — they are the entry point that ties every
subsystem together.

> All five are core database concepts. `Transaction` and `TransactionContext`
> are fundamental database abstractions (not OS concepts). `ThreadPool` is the
> only utility-level class here; it exists to support concurrent transaction
> execution.

---

## Database.java — the central coordinator

This is the main class of the entire system. It creates, owns, and wires
together every manager:

```
Database
 ├─ DiskSpaceManager    — reads/writes 4096-byte pages to OS files
 ├─ BufferManager       — caches pages in memory, runs eviction
 ├─ LockManager         — multigranularity locking (or DummyLockManager to disable)
 ├─ RecoveryManager     — ARIES write-ahead logging (or DummyRecoveryManager to disable)
 ├─ tableMetadata       — a Table storing rows of _metadata.tables (partition 1)
 └─ indexMetadata       — a Table storing rows of _metadata.indices (partition 2)
```

### Constructor chain

Database has five constructors that telescope into each other, adding
defaults at each level:

```java
Database(fileDir)                                          // all defaults
Database(fileDir, numMemoryPages)                          // + buffer size
Database(fileDir, numMemoryPages, lockManager)             // + locking
Database(fileDir, numMemoryPages, lockManager, policy)     // + eviction policy
Database(fileDir, numMemoryPages, lockManager, policy, useRecoveryManager)  // full control
```

The final constructor does the real work:

1. **Setup directory** — create `fileDir` if it doesn't exist; check if
   database files already exist (= this is a restart, not first boot)
2. **Create managers** — DiskSpaceManager, BufferManager, RecoveryManager
3. **Recovery** — allocate log partition (partition 0), run `restart()`
4. **Metadata tables** — if first boot, create `_metadata.tables` (partition 1)
   and `_metadata.indices` (partition 2); if restart, load them from disk

### Metadata tables

Database tracks all user tables and indices via two special tables:

**`_metadata.tables`** — one row per user table:

| Column | Type | Purpose |
|---|---|---|
| table_name | string(32) | Name of the user table |
| part_num | int | Disk partition number |
| page_num | long | First page of the PageDirectory |
| schema | byte_array(4006) | Serialized column names + types |

**`_metadata.indices`** — one row per B+ tree index:

| Column | Type | Purpose |
|---|---|---|
| table_name | string(32) | Table this index belongs to |
| col_name | string(32) | Column being indexed |
| order | int | B+ tree order d |
| part_num | int | Disk partition for all tree pages |
| root_page_num | long | Page number of the root node |
| key_schema_typeid | int | TypeId ordinal of the key type |
| key_schema_typesize | int | Size in bytes of the key type |
| height | int | Current tree height |

Both use `DummyLockContext` (manually synchronized with `synchronized` blocks)
to avoid deadlocks with the regular locking hierarchy.

### Key inner classes

**`TransactionContextImpl`** (extends `TransactionContext`) — the actual
implementation of all record/index operations. This is where the database
"does things":

- `addRecord()` — inserts into the table, then updates **every** index on
  that table
- `deleteRecord()` — deletes from the table, then removes from every index
- `updateRecord()` — updates the table, then does remove+put on every index
- `updateIndexMetadata()` — persists changed BPlusTreeMetadata back to
  `_metadata.indices` (called by `BPlusTree.updateRoot()`)
- `sortedScan()` — if an index exists on the column, uses
  `tree.scanAll()` → `table.recordIterator()`; otherwise falls back to
  `SortOperator` for an in-memory sort
- `lookupKey()` — uses `tree.scanEqual(key)` for indexed point lookups
- `createTempTable()` / `deleteAllTempTables()` — manages temporary tables
  that are visible only to this transaction and cleaned up on commit/abort

**`TransactionImpl`** (extends `Transaction`) — the user-facing transaction
wrapper. Delegates most work to `TransactionContextImpl` but also handles:
- `execute(String sql)` — parses SQL via `RookieParser`, runs through
  `ExecutableStatementVisitor`, returns a `QueryPlan`
- `startCommit()` — deletes temp tables, calls `recoveryManager.commit()`
- `startRollback()` — calls `recoveryManager.abort()`
- `createTable()` / `dropTable()` — creates/removes entries in
  `_metadata.tables` and allocates/frees disk partitions
- `createIndex()` / `dropIndex()` — creates/removes entries in
  `_metadata.indices`, allocates a new B+ tree, and populates it from
  existing table rows

### Locking hierarchy

```
database
 ├─ _metadata.tables
 │   └─ [tableName]           ← lock here to read/write a table's metadata
 ├─ _metadata.indices
 │   └─ [tableName]           ← lock here for all indices on a table
 │       └─ [columnName]      ← lock here for one specific index
 └─ [user tables]
     └─ [pages/records]
```

### Other notable methods

- `close()` — waits for all transactions, evicts all buffer frames, closes
  all managers
- `loadDemo()` / `loadCSV()` — loads CSV files from resources into tables
  (used for the interactive CLI demo)
- `beginTransaction()` — creates a new `TransactionImpl`, registers it with
  the recovery manager, and sets it as the current thread's active
  transaction

---

## Transaction.java — the user-facing transaction interface

This is an **abstract class** that defines what users (and tests) can do
with a transaction. It is the public API; the actual implementation is
`Database.TransactionImpl`.

### Lifecycle

A transaction has a status that progresses through these states:

```
RUNNING → COMMITTING → COMPLETE
RUNNING → ABORTING → COMPLETE
(during recovery: RECOVERY_ABORTING → COMPLETE)
```

The key lifecycle methods:

```java
Transaction t = database.beginTransaction();  // status = RUNNING

t.commit();     // calls startCommit() → COMMITTING → cleanup() → COMPLETE
// OR
t.rollback();   // calls startRollback() → ABORTING → cleanup() → COMPLETE
```

`Transaction` implements `AutoCloseable` — if you use it in a try-with-resources
block without calling commit or rollback, `close()` automatically commits:

```java
try (Transaction t = db.beginTransaction()) {
    t.insert("Students", new Record(...));
    // implicit commit when block exits
}
```

### Categories of operations

**DDL (Data Definition Language):**
- `createTable(schema, tableName)` — CREATE TABLE
- `dropTable(tableName)` — DROP TABLE
- `createIndex(tableName, columnName, bulkLoad)` — CREATE INDEX
- `dropIndex(tableName, columnName)` — DROP INDEX

**DML (Data Manipulation Language):**
- `query(tableName)` → returns a `QueryPlan` for SELECT queries
- `insert(tableName, values...)` — INSERT INTO
- `update(tableName, column, transformFn, ...)` — UPDATE ... SET ... WHERE
- `delete(tableName, predColumn, op, value)` — DELETE ... WHERE

**Savepoints** (require Project 5 recovery):
- `savepoint(name)` — SAVEPOINT
- `rollbackToSavepoint(name)` — ROLLBACK TO SAVEPOINT
- `releaseSavepoint(name)` — RELEASE SAVEPOINT

**Other:**
- `execute(String sql)` — parse and execute a raw SQL string
- `getTransNum()` — transaction number (unique ID)
- `getSchema(tableName)` — get a table's column definitions

### Why this is a database concept

Transactions are the fundamental unit of work in a database. They provide
the **ACID guarantees**: Atomicity (all-or-nothing via commit/rollback),
Consistency (type checking, constraint enforcement), Isolation (locking
prevents conflicts), Durability (recovery manager persists committed
changes). This class defines the boundary of those guarantees.

---

## TransactionContext.java — the internal transaction interface

While `Transaction` is the user-facing API (create tables, run queries),
`TransactionContext` is the **internal** API used by the database engine
itself — query operators, table code, and index code call these methods.

### Thread-local transaction tracking

The most important pattern in this class: it maintains a **thread-local map**
from thread ID to the currently running transaction context.

```java
static Map<Long, TransactionContext> threadTransactions = new ConcurrentHashMap<>();

// Set when a transaction begins on this thread
TransactionContext.setTransaction(ctx);

// Get the current transaction from anywhere in the codebase
TransactionContext ctx = TransactionContext.getTransaction();

// Clear when the transaction ends
TransactionContext.unsetTransaction();
```

This is how code deep inside the engine (like `BPlusTree.updateRoot()`) can
access the current transaction without having it passed as a parameter
through every method call:

```java
// Inside BPlusTree.updateRoot():
TransactionContext transaction = TransactionContext.getTransaction();
if (transaction != null) {
    transaction.updateIndexMetadata(metadata);
}
```

### Key abstract methods

These are implemented by `Database.TransactionContextImpl`:

**Record operations** — the bridge between query operators and tables:
- `addRecord(tableName, record)` → `RecordId`
- `deleteRecord(tableName, rid)` → `RecordId`
- `updateRecord(tableName, rid, updated)` → `RecordId`
- `getRecord(tableName, rid)` → `Record`

**Index-aware scans** — used by query operators for indexed access:
- `sortedScan(tableName, columnName)` — ordered scan (uses index if available)
- `sortedScanFrom(tableName, columnName, startValue)` — range scan from a value
- `lookupKey(tableName, columnName, key)` — point lookup via index
- `getRecordIterator(tableName)` — full table scan (no index)

**Schema and statistics** — used by the query optimizer:
- `getSchema(tableName)` / `getFullyQualifiedSchema(tableName)`
- `getStats(tableName)` — histogram-based statistics for cost estimation
- `getNumDataPages(tableName)` — for I/O cost calculation
- `getTreeOrder(tableName, columnName)` — B+ tree order d
- `getTreeHeight(tableName, columnName)` — B+ tree height

**Temp tables** — used by query operators for intermediate results:
- `createTempTable(schema)` — allocates a temporary table
- `deleteAllTempTables()` — cleans up on transaction end

**Metadata:**
- `updateIndexMetadata(metadata)` — persists B+ tree root/height changes
- `indexExists(tableName, columnName)` — checks if an index is available

### Blocking/synchronization (for concurrency, Project 4)

```java
ctx.prepareBlock();  // acquire internal lock (must call before block)
ctx.block();         // suspend this thread until unblock() is called
ctx.unblock();       // wake up the blocked thread (called from LockManager)
```

This is how the lock manager pauses a transaction that's waiting for a lock:
it calls `block()` on the requesting transaction's context, and `unblock()`
when the lock becomes available. This is a database-level concurrency
mechanism, not an OS-level one — it coordinates transactions, not arbitrary
threads.

---

## DatabaseException.java — the standard error type

A simple `RuntimeException` subclass used throughout the codebase:

```java
public class DatabaseException extends RuntimeException {
    public DatabaseException(String message) { super(message); }
    public DatabaseException(Exception e)    { super(e); }
}
```

Thrown for any database-level error:
- Table or index already exists / doesn't exist
- Invalid table or column names
- Schema violations
- Directory setup failures

Because it extends `RuntimeException` (unchecked), callers are not forced to
catch it — it propagates up the call stack until caught by the transaction
or CLI layer.

---

## ThreadPool.java — concurrent transaction execution

A thin wrapper around Java's `ThreadPoolExecutor`. This is the only
utility/infrastructure class in the package — it exists to support running
multiple transactions concurrently.

```java
class ThreadPool extends ThreadPoolExecutor {
    ThreadPool() {
        super(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS, new SynchronousQueue<>());
    }
}
```

Configuration: zero core threads, unbounded max, 60-second idle timeout,
synchronous handoff (no queuing — creates a new thread immediately if none
are idle).

Its only custom behavior is `afterExecute()`: if a task running in the pool
throws an exception, this method catches it and re-throws it so it doesn't
get silently swallowed. Without this override, exceptions thrown inside
`Future` tasks would be lost.

You will not need to modify or directly interact with this class in any
project. It is used internally by the database to run transactions on
separate threads during concurrency testing (Project 4).

---

## How They Fit Together

```
User code / Test / CLI
        │
        ▼
   Transaction              ← public API: commit, rollback, createTable, query, insert...
        │ delegates to
        ▼
   TransactionContext        ← internal API: addRecord, getRecordIterator, updateIndexMetadata...
        │ implemented by
        ▼
   Database                  ← central coordinator: owns all managers, metadata tables
   .TransactionContextImpl       wires together Table, BPlusTree, BufferManager, etc.
        │
        ├──→ Table (table/)         for record storage
        ├──→ BPlusTree (index/)     for index lookups
        ├──→ BufferManager (memory/) for page caching
        ├──→ LockManager (concurrency/) for isolation
        └──→ RecoveryManager (recovery/) for durability
```

The separation between `Transaction` (public) and `TransactionContext`
(internal) exists so that user code cannot call engine-internal methods
like `addRecord` or `updateIndexMetadata` directly — those go through
the higher-level `insert()` / `query()` API that enforces validation,
locking, and index maintenance.