# Concurrency Module: Multigranularity Locking

## Architecture Overview

The concurrency module implements a **multigranularity locking** (MGL) system organized into three layers:

```
LockUtil  (declarative "ensure I have at least this lock")
    |
LockContext  (enforces hierarchy rules: parent/child, numChildLocks)
    |
LockManager  (flat lock bookkeeping: grant, queue, release, promote)
```

Application code should call `LockUtil.ensureSufficientLockHeld(...)` or `LockContext` methods. Direct `LockManager` calls are reserved for the context layer.

---

## Lock Types (LockType.java)

Six lock types form a lattice:

| Type | Meaning | Intent? |
|------|---------|---------|
| `NL` | No lock | No |
| `S` | Shared -- read access | No |
| `X` | Exclusive -- read+write access | No |
| `IS` | Intention Shared -- some descendant holds S | Yes |
| `IX` | Intention Exclusive -- some descendant holds X | Yes |
| `SIX` | Shared + Intention Exclusive -- read this node, write some descendant | Yes |

### Compatibility matrix (to be implemented in `compatible()`)

Two locks from **different transactions** on the **same resource** are compatible when:

|       | NL | IS | IX | S  | SIX | X  |
|-------|----|----|----|----|----|-----|
| **NL**  | Y  | Y  | Y  | Y  | Y   | Y  |
| **IS**  | Y  | Y  | Y  | Y  | Y   | N  |
| **IX**  | Y  | Y  | Y  | N  | N   | N  |
| **S**   | Y  | Y  | N  | Y  | N   | N  |
| **SIX** | Y  | Y  | N  | N  | N   | N  |
| **X**   | Y  | N  | N  | N  | N   | N  |

### Parent-child rules (`parentLock()` and `canBeParentLock()`)

Before acquiring a lock on a child resource, the transaction must hold a suitable **intent lock** on the parent:

| Child lock | Required parent lock |
|------------|---------------------|
| `S`        | `IS` (or stronger: `IX`, `SIX`) |
| `X`        | `IX` (or stronger: `SIX`) |
| `IS`       | `IS` (or stronger) |
| `IX`       | `IX` (or stronger) |
| `SIX`      | `IX` (or stronger) |
| `NL`       | `NL` (anything) |

`parentLock(type)` returns the **minimum** parent lock. `canBeParentLock(parent, child)` checks whether `parent` is sufficient to grant `child`.

### Substitutability (`substitutable()`)

Lock type A can substitute for B if A grants **at least** the permissions of B. For example, `X` substitutes for `S`, and `SIX` substitutes for `S`, `IX`, and `IS`.

---

## Resource Naming (ResourceName.java)

Resources are identified by an ordered list of strings forming a path in the hierarchy:

```
["database"]                          -- the whole database
["database", "myTable"]               -- a table
["database", "myTable", "42"]         -- page 42 of myTable
```

Key methods:
- `parent()` -- returns the `ResourceName` with the last component stripped, or `null` at the root.
- `isDescendantOf(other)` -- prefix check on the name list.

`LockContext` builds `ResourceName`s automatically when you call `childContext(name)`.

---

## Lock Object (Lock.java)

A simple value object with three public fields:

```java
public ResourceName name;
public LockType lockType;
public Long transactionNum;
```

Equality is defined over all three fields -- the same transaction can hold different lock types on different resources, and each combination is a distinct `Lock`.

---

## Lock Request (LockRequest.java)

Represents a pending request on a resource's wait queue:

```java
TransactionContext transaction;
Lock lock;                    // the lock being requested
List<Lock> releasedLocks;     // locks to release atomically when this request is granted
```

The `releasedLocks` list is used by `acquireAndRelease()` to atomically swap locks (e.g., during escalation: acquire S on table, release S on pages).

---

## LockManager.java -- Core Lock Bookkeeping

### Data structures

- `transactionLocks`: `Map<Long, List<Lock>>` -- all locks held by each transaction (by transaction number).
- `resourceEntries`: `Map<ResourceName, ResourceEntry>` -- per-resource state.

### ResourceEntry (inner class)

Each resource has:
- `locks`: `List<Lock>` -- currently granted locks on the resource.
- `waitingQueue`: `Deque<LockRequest>` -- FIFO queue of pending requests.

Helper methods (all TODO in the skeleton):
- `checkCompatible(lockType, except)` -- checks `lockType` against all current locks, ignoring locks held by transaction `except` (used for promotions where the transaction already holds a lock).
- `grantOrUpdateLock(lock)` -- adds/updates a lock in both `locks` and `transactionLocks`.
- `releaseLock(lock)` -- removes the lock and calls `processQueue()`.
- `addToQueue(request, addFront)` -- inserts at front (for promote/acquireAndRelease) or back (for regular acquire).
- `processQueue()` -- iterates from the front, granting compatible requests and unblocking their transactions, stopping at the first incompatible one. This means in a queue like `[S(A), X(A), S(A)]`, only the first `S(A)` is granted when processed, because the `X(A)` blocks further processing.
- `getTransactionLockType(transaction)` -- returns what lock a specific transaction holds on this resource.

### Public API

All public methods follow the pattern:
1. Error checking.
2. Inside `synchronized(this)`: modify state, determine if the transaction should block.
3. Outside the synchronized block: call `transaction.block()` if needed.

This two-phase pattern prevents deadlocks between the lock manager's monitor lock and the transaction's block mechanism.

| Method | Queue position | Key behavior |
|--------|---------------|--------------|
| `acquire()` | Back | Throws `DuplicateLockRequestException` if already held. Blocks if incompatible **or** if any request is already queued for the resource. |
| `acquireAndRelease()` | Front | Acquires a new lock and atomically releases a set of named locks afterward. Used for lock escalation. Does NOT block on the release side. |
| `promote()` | Front | Changes an existing lock to a stronger type. Validates substitutability. |
| `release()` | N/A | Releases a lock and processes the queue. |
| `getLockType()` | N/A | Returns what type the transaction currently holds (or `NL`). |

### Important invariant: acquisition order preservation

Both `promote()` and `acquireAndRelease()` explicitly preserve the original acquisition order of locks. This matters because `getLocks(transaction)` returns locks in acquisition order, and downstream code (like `sisDescendants` in `LockContext`) iterates over this list.

---

## LockContext.java -- Hierarchical Lock Management

`LockContext` adds MGL rules on top of `LockManager`. Each context represents one node in the resource hierarchy and knows its parent, children, and how many child locks each transaction holds.

### Key fields

- `lockman` -- reference to the shared `LockManager`.
- `parent` -- the parent `LockContext` (null for the database root).
- `name` -- the `ResourceName` for this node.
- `numChildLocks` -- `Map<Long, Integer>`: for each transaction, how many locks it holds on direct children of this context. This must be maintained manually in every acquire/release/promote/escalate operation.
- `children` -- `Map<String, LockContext>`: cached child contexts.
- `readonly` / `childLocksDisabled` -- when set, all mutating operations throw `UnsupportedOperationException`.

### Operations (all TODO in the skeleton)

**`acquire(transaction, lockType)`**
Must check that the parent holds a sufficient lock (via `canBeParentLock`), then delegate to `lockman.acquire()`, and increment the parent's `numChildLocks`.

**`release(transaction)`**
Must check that no child holds a lock that would become invalid without this context's lock, then delegate to `lockman.release()`, and decrement the parent's `numChildLocks`.

**`promote(transaction, newLockType)`**
Standard promotion delegates to `lockman.promote()`. The special case is promotion to **SIX** from IS/IX/S:
- SIX already implies S on all descendants, so any S or IS locks on descendants become redundant and must be released simultaneously.
- The helper `sisDescendants(transaction)` collects all descendant `ResourceName`s where the transaction holds S or IS.
- The promotion uses `lockman.acquireAndRelease()` to atomically acquire SIX and release those descendant locks.

**`escalate(transaction)`**
Converts fine-grained descendant locks into a single coarse lock at this level:
1. Collects all descendant locks held by the transaction.
2. Determines the escalated type: S if all descendants are S/IS, otherwise X.
3. Uses `lockman.acquireAndRelease()` to atomically acquire the new lock and release all descendant locks.
4. Resets `numChildLocks` to 0 for all affected ancestor contexts.
5. Is a no-op if the locks would not actually change.

**`getExplicitLockType(transaction)`**
Returns the lock type held directly at this level.

**`getEffectiveLockType(transaction)`**
Walks up the hierarchy: if an ancestor holds S, SIX, or X, those permissions flow down implicitly. For example, S at the table level implies S at every page level, even if no explicit page lock exists.

### Helper methods

- `hasSIXAncestor(transaction)` -- walks up the parent chain checking for SIX locks. Used to reject redundant S/IS acquisitions under a SIX ancestor.
- `sisDescendants(transaction)` -- iterates over all locks held by the transaction and filters for S/IS locks on descendants of the current context.
- `fromResourceName(lockman, name)` -- reconstructs a `LockContext` chain from a `ResourceName` by walking through `lockman.context(root).childContext(next)...`.

---

## LockUtil.java -- Declarative Lock Acquisition

`ensureSufficientLockHeld(lockContext, requestType)` is the recommended entry point for application code. Given a context and a desired lock type (guaranteed to be S, X, or NL), it ensures the transaction holds at least that permission at that level. The logic should handle:

1. **Already sufficient**: if `getEffectiveLockType()` already substitutes for the request, do nothing.
2. **IX + need S**: promote to SIX (since IX is for writing descendants, and S is for reading this node).
3. **Intent lock held (IS or IX)**: escalate to convert intent locks to actual locks.
4. **No lock / NL**: acquire appropriate intent locks on all ancestors first (bottom-up), then acquire the actual lock.

This is the **only place** where ancestor lock acquisition is handled automatically -- all other methods assume the caller has already set up the hierarchy.

---

## The Locking Hierarchy in Database.java

The database uses this resource tree:

```
database
 +-- _metadata.tables
 |    +-- myTable          (metadata about myTable)
 |    +-- otherTable
 +-- _metadata.indices
 |    +-- someTable
 |         +-- rowId       (specific index)
 +-- myTable               (the actual table data)
 |    +-- 42               (page 42)
 |    +-- 43               (page 43)
 +-- otherTable
      +-- ...
```

Note that user tables exist as **direct children of database** (e.g., `database/myTable`), while their metadata lives under `database/_metadata.tables/myTable`. This separation allows independent locking of data vs. metadata.

`Database.java` resolves contexts with helpers like:
- `getTableContext(tableName)` -> `lockManager.databaseContext().childContext(tableName)`
- `getTableMetadataContext(tableName)` -> `getTableInfoContext().childContext(tableName)`
- `getColumnIndexMetadataContext(table, col)` -> `getIndexInfoContext().childContext(table).childContext(col)`

Table operations call `LockUtil.ensureSufficientLockHeld(getTableContext(name), LockType.S)` (for reads) or `LockType.X` (for writes), which triggers the full hierarchy setup.

---

## Lock Escalation in Detail

Escalation is a performance optimization. When a transaction holds many fine-grained page locks under a table, the overhead of tracking them exceeds the benefit. Escalation replaces them with one table-level lock:

**Before:**
```
IX(database) -> IX(table1) -> S(page3), X(page5), S(page7), ...
```

**After:**
```
IX(database) -> X(table1)
```

The escalated type is:
- **S** if the current lock at this level is S/IS and all descendant locks are S or IS.
- **X** otherwise (if any descendant is X, IX, or SIX).

This uses a single `acquireAndRelease()` call, making it atomic: the new coarse lock is acquired and all fine-grained locks are released in one step.

---

## Dummy Implementations

**`DummyLockManager`** extends `LockManager` with all methods as no-ops. Every `context()` call returns a `DummyLockContext`. `getLockType()` always returns `NL`, and `getLocks()` always returns an empty list.

**`DummyLockContext`** extends `LockContext` with all operations as no-ops. `childContext()` returns a new `DummyLockContext`, and lock type queries always return `NL`.

These are used in two scenarios:
1. **Testing without locking**: early projects (before Project 4 is implemented) use `DummyLockManager` so that locking is disabled.
2. **Resources that do not need locking**: temporary tables (only accessible by one transaction) and internal structures like metadata page directories use `DummyLockContext` directly.

---

## Exception Types

- `DuplicateLockRequestException` -- thrown when acquiring a lock the transaction already holds.
- `NoLockHeldException` -- thrown when releasing or promoting a lock the transaction does not hold.
- `InvalidLockException` -- thrown when a lock operation violates MGL rules (e.g., promoting to a non-substitutable type, acquiring a child lock without the proper parent lock).
