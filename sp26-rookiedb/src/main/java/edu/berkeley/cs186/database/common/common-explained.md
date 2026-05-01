# common/ -- Shared Utilities

This module contains low-level building blocks used throughout RookieDB: byte buffers for serialization, backtracking iterators for query processing, and miscellaneous helpers.

## Buffer System (Buffer, AbstractBuffer, ByteBuffer)

### Buffer.java -- The Interface

`Buffer` defines a positional read/write cursor over a sequence of bytes, modeled after `java.nio.ByteBuffer`. Every `get*()` / `put*()` method comes in two forms:

- **Relative** (no index argument): reads/writes at the current position and advances it.
- **Absolute** (with `int index`): reads/writes at a specific offset, position unchanged.

Supported primitive types: `byte`, `char`, `short`, `int`, `long`, `float`, `double`, plus bulk `byte[]` operations. Put methods return `Buffer` for chaining:

```java
buf.putChar('c').putChar('s').putInt(186);
```

The interface also exposes `position()`, `position(int)`, `slice()`, and `duplicate()`.

**Why a custom Buffer interface?** The codebase needs buffers backed by different storage: plain byte arrays (for in-memory work) and page-backed storage (for disk I/O). The `Buffer` interface abstracts over both. The `databox` module's `DataBox.fromBytes(Buffer, Type)` accepts any `Buffer`, whether it points into a heap array or a disk page.

### AbstractBuffer.java -- Template for Page-Backed Buffers

`AbstractBuffer` provides a partial implementation where only two methods are abstract:

- `get(byte[] dst, int offset, int length)` -- bulk read from storage
- `put(byte[] src, int offset, int length)` -- bulk write to storage

Everything else is built on top of these two. It uses an internal 8-byte scratch `byte[]` plus a `java.nio.ByteBuffer` wrapper to handle type conversions (e.g., reading 4 bytes then calling `ByteBuffer.getInt(0)`).

The position tracking is manual (`private int pos`). Each relative method increments `pos` by the type's byte width, then delegates to the absolute version.

This class exists primarily to be extended by the buffer manager's page-backed buffer implementation. By overriding only the two abstract methods, a page buffer can read/write through the OS page cache while getting all typed access for free.

### ByteBuffer.java -- The Concrete In-Memory Buffer

A thin wrapper around `java.nio.ByteBuffer`. Does **not** extend `AbstractBuffer`--it delegates every method directly to the underlying NIO buffer. This avoids the scratch-array overhead of `AbstractBuffer` when working with plain heap memory.

Additional methods beyond the `Buffer` interface: `capacity()`, `limit()`, `remaining()`, `hasRemaining()`, `flip()`, `rewind()`, `clear()`, `mark()`, `reset()`, `array()`, `order()`.

Factory methods mirror NIO: `ByteBuffer.allocate(n)`, `ByteBuffer.wrap(byte[])`, `ByteBuffer.wrap(byte[], offset, length)`.

**Common usage pattern:** Serialize a record by allocating a `ByteBuffer` of the right size, calling `putInt`/`putFloat`/`put(byte[])` for each field, then calling `array()` to get the raw bytes.

## BacktrackingIterator System

### BacktrackingIterator<T> -- The Interface

Extends `java.util.Iterator<T>` with three additional methods:

- **`markPrev()`** -- marks the most recently returned element.
- **`markNext()`** -- marks the element that `next()` will return.
- **`reset()`** -- rewinds the iterator to the marked position. After reset, the next call to `next()` returns the marked element. Can be called repeatedly to revisit the same mark.

This abstraction is essential for join algorithms (nested loop joins need to re-scan the inner relation for each outer tuple) and table scans that need checkpoint/resume behavior.

Example usage pattern from a nested loop join:
```java
innerIterator.markNext();       // mark start of inner relation
while (innerIterator.hasNext()) {
    Record inner = innerIterator.next();
    // ... try to join with current outer record ...
}
innerIterator.reset();           // rewind for next outer record
```

**Contract subtlety:** `markPrev()` on a fresh iterator (or one that hasn't yielded since last `reset()`) is a no-op. `markNext()` when `hasNext()` is false is a no-op. `reset()` with no mark set is a no-op.

### IndexBacktrackingIterator<T> -- Abstract Index-Based Implementation

Handles all mark/reset bookkeeping using integer indices. Subclasses provide:

- **`getNextNonEmpty(int currentIndex)`** -- returns the next valid index after `currentIndex` (initial call uses -1). Returns `>= maxIndex` when exhausted.
- **`getValue(int index)`** -- returns the element at a given index.

State is tracked with three indices: `prevIndex`, `nextIndex`, `markIndex` (all initially -1). The `hasNext/next` logic calls `getNextNonEmpty` lazily. Mark and reset simply save/restore index values.

The "non-empty" concept exists because some index-based collections (like page slot arrays) can have gaps. The `getNextNonEmpty` abstraction lets subclasses skip deleted or empty slots.

### ArrayBacktrackingIterator<T>

Extends `IndexBacktrackingIterator`. Wraps a `T[]` or `List<T>`. `getNextNonEmpty` simply returns `currentIndex + 1` (no gaps). `getValue` indexes into the array.

### ConcatBacktrackingIterator<T>

Concatenates multiple `BacktrackingIterable<T>` objects into a single backtracking iterator. Internally tracks three iterators:

- `prevItemIterator` -- the sub-iterator that yielded the last item
- `nextItemIterator` -- the sub-iterator that will yield the next item
- `markItemIterator` -- the sub-iterator containing the marked item

Each has a corresponding index into the list of iterables. When `nextItemIterator` is exhausted, `moveNextToNonEmpty()` advances to the next non-empty sub-iterator, lazily pulling from the outer iterator and caching iterables in a list (needed for reset to work--you can't re-iterate a consumed iterator).

**Mark/reset delegation:** When `markPrev()` is called, it records _which_ sub-iterator holds the marked item and calls `markPrev()` on that sub-iterator. On `reset()`, it restores `nextItemIterator` to the marked sub-iterator and calls `reset()` on it. This means marks work correctly even across iterable boundaries.

### EmptyBacktrackingIterator<T>

`hasNext()` always returns false. All mark/reset operations are no-ops. Used as the initial `nextItemIterator` in `ConcatBacktrackingIterator` and anywhere an empty result set is needed.

### BacktrackingIterable<T>

A simple interface: `Iterable<T>` whose `iterator()` returns a `BacktrackingIterator<T>`. This exists so that `ConcatBacktrackingIterator` can accept iterables that produce the right iterator type.

## Other Utilities

### Pair<A, B>

Immutable generic pair with `getFirst()` / `getSecond()`. Implements `equals` and `hashCode` correctly (null-safe, component-wise). Used throughout the codebase for returning two values, building composite map keys, and representing table-alias associations (e.g., `Pair<String, String>` for temp table name to alias in the query planner).

### PredicateOperator

Enum of six comparison operators: `EQUALS`, `NOT_EQUALS`, `LESS_THAN`, `LESS_THAN_EQUALS`, `GREATER_THAN`, `GREATER_THAN_EQUALS`.

Key methods:
- **`evaluate(T a, T b)`** -- evaluates the comparison on any `Comparable<T>`. Delegates to `compareTo` and checks the sign. This is how WHERE clause predicates are applied to `DataBox` values during query execution.
- **`fromSymbol(String)`** -- parses operator strings: `"="`, `"=="`, `"!="`, `"<>"`, `"<"`, `"<="`, `">"`, `">="`. Used by the SQL parser.
- **`toSymbol()`** -- returns canonical string form (e.g., `EQUALS` -> `"="`).
- **`reverse()`** -- flips the operator as if the operands were swapped: `LESS_THAN` becomes `GREATER_THAN`, etc. `EQUALS` and `NOT_EQUALS` are unchanged. Used in `ColumnValueComparisonVisitor` to normalize `5 > col` into `col < 5`.

### Bits

Bit manipulation on bytes and byte arrays, with **MSB-first** indexing (bit 0 is the most significant bit).

- `getBit(byte, int)` / `getBit(byte[], int)` -- read a single bit.
- `setBit(byte, int, Bit)` / `setBit(byte[], int, Bit)` -- set a single bit.
- `countBits(byte)` / `countBits(byte[])` -- population count (number of 1-bits).

Used for bitmap headers in heap pages where each bit tracks whether a record slot is occupied.

### HashFunc

Postgres-compatible hash functions for hash joins and hash-based partitioning.

- **`hashDataBox(DataBox d, int pass)`** -- hashes a single DataBox. Calls `d.hashBytes()` (not `d.toBytes()`), which is why `StringDataBox.hashBytes()` strips padding.
- **`hashRecord(Record record, int pass)`** -- concatenates the `hashBytes()` of all fields, then hashes the result.
- **`hashBytes(byte[] k, long seed)`** -- the core hash function. Based on Postgres's `hash_bytes_extended`, using a three-variable mixing state (`a`, `b`, `c`). Processes input in 12-byte chunks with bit rotations and XORs.

The `pass` parameter seeds the hash differently for each partitioning pass, enabling recursive partitioning in grace hash joins: pass 0 partitions into initial buckets, pass 1 re-partitions overflow buckets with a different hash function, etc. The hash value is a signed 32-bit integer (can be negative).
