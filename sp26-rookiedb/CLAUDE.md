# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

RookieDB — a bare-bones database implementation for UC Berkeley CS186 (SP26). Java 8, Maven build, JUnit 4 tests. Projects span B+ tree indices, join algorithms, query optimization, multigranularity locking, and ARIES recovery.

## Build & Test Commands

```bash
mvn clean compile

# Run tests for a specific project (N = 0, 2, 3, 4, 5)
mvn clean test -Dproj=2

# Public tests only
mvn clean test -Dproj=2 -Ppublic

# Single test class or method
mvn clean test -Dproj=2 -Dtest=TestLeafNode
mvn clean test -Dproj=2 -Dtest=TestLeafNode#testFromBytes
```

Test categories: `Proj0Tests` through `Proj5Tests`, selected via `-Dproj=N`. Profiles `-Ppublic`, `-Phidden`, `-Pstudent` filter further. JVM memory is capped at 32 MB (pom.xml argLine). Project 3 has sub-categories: `Proj3Part1Tests`, `Proj3Part2Tests`. Project 4 has: `Proj4Part1Tests`, `Proj4Part2Tests`, `Proj4IntegrationTests`.

## Layered Architecture

```
SQL String → CLI parser (cli/parser/) → AST visitors (cli/visitor/)
  → QueryPlan (query/) → QueryOperator DAG → Iterator<Record>
    → Table / BPlusTree (table/, index/) → PageDirectory
      → BufferManager (memory/) → DiskSpaceManager (io/)
        ↕ RecoveryManager (recovery/) — write-ahead logging
        ↕ LockManager (concurrency/) — multigranularity locking
```

**Database.java** is the central coordinator: creates all managers, owns metadata tables (`_metadata.tables`, `_metadata.indices`), and spawns transactions.

### Storage Layer (`io/`, `memory/`)

- **DiskSpaceManager**: partitions → OS files, virtual page numbers, 4096-byte pages
- **BufferManager**: page cache with pin/unpin lifecycle. `EFFECTIVE_PAGE_SIZE = PAGE_SIZE - 36` (36B reserved for recovery LSN). Eviction policies: Clock (default) or LRU
- **Page**: delegates to BufferFrame; `getBuffer()` returns a `Buffer` with positional cursor (like `java.nio.ByteBuffer`)

### Table Layer (`table/`)

- **Table**: heap file backed by PageDirectory. Insert/get/update/delete by RecordId (pageNum + slot)
- **PageDirectory**: header pages track free space on data pages; allocates from partition on demand
- **Record**: immutable list of DataBox values. **Schema**: field names + Types

### Index Layer (`index/`) — B+ Tree

```
BPlusTree            — public API: get, put, remove, scanAll, scanGreaterEqual, bulkLoad
  └─ BPlusNode       — abstract base; dispatches fromBytes by 1-byte discriminator
       ├─ InnerNode  — keys[] + children[] (page numbers); n keys → n+1 children
       └─ LeafNode   — keys[] + rids[] + rightSibling; linked list across leaves
BPlusTreeMetadata    — order d, key Type, root page number, partition, height
```

Each node occupies one page. After mutation, `sync()` serializes via `toBytes()` and writes to the page's Buffer. On read, `fromBytes()` fetches the page and reconstructs in-memory lists.

**Stale-cache hazard:** Two node objects pointing to the same page have independent in-memory lists. A write through one does NOT update the other.

**Table ↔ Index integration:** Table.addRecord/updateRecord/deleteRecord automatically update all indices on that table.

### Query Layer (`query/`)

- **QueryPlan**: builder pattern accumulating SELECT/FROM/WHERE/JOIN/GROUP BY/ORDER BY/LIMIT, then `execute()` builds an operator DAG
- **QueryOperator**: base class; each operator wraps source(s) and implements `Iterator<Record>` + `estimateIOCost()`
- **Join operators** (`query/join/`): SNLJ, PNLJ, BNLJ, SortMerge, GHJ (Grace Hash Join)
- **Disk utilities** (`query/disk/`): `Run` (sorted run for external sort), `Partition` (buffer partition for hash joins)

### Concurrency Layer (`concurrency/`)

Lock types: NL, S, X, IS, IX, SIX. Hierarchy: database → table → page/record. `LockContext` manages parent-child relationships and tracks `numChildLocks`. `LockUtil.ensureSufficientLockHeld()` enforces minimum lock level. `DummyLockManager`/`DummyLockContext` disable locking for tests.

### Recovery Layer (`recovery/`)

ARIES protocol via `ARIESRecoveryManager`. Partition 0 reserved for log. BufferManager calls `pageFlushHook()` before evicting dirty pages (write-ahead logging guarantee). On restart: analysis → redo → undo phases.

## Type System (`databox/`)

`DataBox.toBytes()` writes only the value — no type tag. Deserialization requires the `Type` explicitly: `DataBox.fromBytes(Buffer, Type)`.

| TypeId     | Size     | Wire format                  |
|------------|----------|------------------------------|
| BOOL       | 1B       | `0x00` or `0x01`             |
| INT        | 4B       | big-endian int               |
| FLOAT      | 4B       | big-endian float             |
| LONG       | 8B       | big-endian long              |
| STRING(n)  | n bytes  | raw UTF-8, fixed-width       |
| BYTE_ARRAY | n bytes  | raw bytes, fixed-width       |

**RecordId**: `| pageNum (8B) | entryNum (2B) |` — total 10 bytes.

## B+ Tree Serialization Formats

### LeafNode

```
| isLeaf=1 (1B) | rightSiblingPageNum (8B) | numEntries (4B) | [key.toBytes() + rid.toBytes()] × n |
```
- `rightSiblingPageNum` = -1 if no right sibling
- Header = 13 bytes; each entry = `keySize + 10` bytes
- Max order: `d = ((pageSize - 13) / (keySize + ridSize)) / 2`
- **`fromBytes` is TODO(proj2)** — must use the private constructor that takes an existing `Page`, not the one that allocates a new page

### InnerNode

```
| isLeaf=0 (1B) | numKeys (4B) | [key.toBytes()] × n | [childPageNum (8B)] × (n+1) |
```
- Keys and child pointers stored in separate contiguous blocks (not interleaved)
- Max order: `d = ((pageSize - 13) / (keySize + 8)) / 2`
- `fromBytes` is already implemented — use as reference for `LeafNode.fromBytes`

### BPlusNode.fromBytes — Type Dispatch

First byte of page: `0x01` → LeafNode, `0x00` → InnerNode.

## B+ Tree Invariants & Split Rules

- Order `d`: inner nodes hold d–2d keys; leaves hold d–2d (key, rid) pairs. Root and deleted leaves may violate the lower bound
- No duplicate keys — `put` throws `BPlusTreeException`
- `remove` does NOT rebalance
- **Leaf split:** left keeps `d` entries, right gets `d+1`; split key = first key in right node
- **Inner split:** left keeps `d` keys, right gets `d`; middle key pushed up (moved, not copied)
- `put`/`bulkLoad` return `Optional<Pair<DataBox, Long>>`: empty = no overflow; present = (splitKey, newPageNum) for parent to insert. Root splits use `BPlusTree.updateRoot()`
- `scanAll`/`scanGreaterEqual` must use a lazy `BPlusTreeIterator` that walks the leaf linked list — materializing all rids scores 0 points
- `bulkLoad` uses `fillFactor` only for leaves (round up); inner nodes fill to 2d then split normally

### Navigation Helpers (InnerNode)

- `numLessThanEqual(x, sortedList)` — count of elements ≤ x; use for point lookups
- `numLessThan(x, sortedList)` — count of elements < x; use for range scans

## Utility Patterns

- **BacktrackingIterator** (`common/iterator/`): iterator with mark/reset capability used throughout table scans and join operators
- **Buffer** (`common/`): positional read/write API wrapping byte arrays; sequential reads advance cursor automatically
- **Pair** (`common/`): generic pair used as return type for split operations
