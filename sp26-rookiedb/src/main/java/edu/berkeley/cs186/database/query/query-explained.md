# Query Execution Engine

This module implements a Volcano-style iterator query execution engine. Queries are represented as a DAG of `QueryOperator` nodes, each of which lazily produces records via a standard `Iterator<Record>` interface.

## Architecture Overview

```
SQL string (parsed externally)
        |
        v
   QueryPlan          -- builder: collect tables, joins, selects, projects, etc.
        |  .execute() or .executeNaive()
        v
   QueryOperator DAG  -- tree of operators; root is finalOperator
        |  .iterator()
        v
   Iterator<Record>   -- pull-based: each next() call propagates down the tree
```

---

## QueryOperator.java -- Base Class

All operators extend `QueryOperator`. It defines the contract:

- **`iterator()`** -- returns an `Iterator<Record>` over this operator's output.
- **`computeSchema()`** -- determines the output schema based on the source operator(s).
- **`estimateStats()`** -- returns a `TableStats` estimate for cost-based optimization.
- **`estimateIOCost()`** -- returns an I/O cost estimate (number of page reads/writes).
- **`backtrackingIterator()`** -- optional; throws by default. Operators that are materialized to disk (scans, sorts, materialized operators) override this.
- **`materialized()`** -- returns `true` if the operator's records are stored in a temp table (enabling backtracking and random access).

### OperatorType enum

```java
PROJECT, SEQ_SCAN, INDEX_SCAN, JOIN, SELECT, GROUP_BY, SORT, LIMIT, MATERIALIZE
```

### Source chaining

Most operators have a single `source` field (their child in the DAG). The constructor `QueryOperator(type, source)` automatically calls `computeSchema()`. Join operators override `getSource()` to throw -- they have `leftSource` and `rightSource` instead.

### Helper: getBlockIterator

```java
static BacktrackingIterator<Record> getBlockIterator(
    Iterator<Record> records, Schema schema, int maxPages)
```

Consumes up to `maxPages` pages worth of records from the iterator and returns them as a `BacktrackingIterator`. This is the core building block for block-based join algorithms (BNLJ, PNLJ).

### Helper: materialize

```java
static QueryOperator materialize(QueryOperator op, TransactionContext tx)
```

Wraps an operator in a `MaterializeOperator` if it is not already materialized. Used by join operators that need to backtrack over their right input.

---

## Scan Operators

### SequentialScanOperator

The leaf node for most query plans. Has no source operator -- it reads directly from a table.

- `iterator()` delegates to `backtrackingIterator()`, which calls `transaction.getRecordIterator(tableName)`.
- Schema is the fully qualified schema from the transaction (e.g., `tableName.col1`, `tableName.col2`).
- `estimateIOCost()` = number of data pages in the table.
- Always `materialized() = true` (backed by a real table on disk).

### IndexScanOperator

Uses a B+ tree index to read only records matching a predicate on the indexed column.

- For `EQUALS`: uses `transaction.lookupKey()`.
- For `LESS_THAN` / `LESS_THAN_EQUALS`: uses `transaction.sortedScan()` (starts from the beginning) and stops when the predicate fails.
- For `GREATER_THAN` / `GREATER_THAN_EQUALS`: uses `transaction.sortedScanFrom(value)` and skips equal values for strict `>`.
- Reports `sortedBy()` on the indexed column, which lets downstream operators (SortMergeJoin, SortOperator) skip redundant sorts.
- Cost estimate: `treeHeight + ceil(matchingRecords / (1.5 * order)) + matchingRecords`.

---

## Filter / Transform Operators

### SelectOperator

Filters records from its source using a column-predicate-value comparison (`=`, `!=`, `<`, `<=`, `>`, `>=`).

- Schema is unchanged (pass-through from source).
- `estimateIOCost()` equals the source's cost (no additional I/O -- it filters in-memory).
- `estimateStats()` uses `TableStats.copyWithPredicate()` to estimate the output cardinality.
- Iterator: pulls from source, yields only records where the comparison holds.

### ProjectOperator

Transforms records by evaluating a list of `Expression` objects and building new records from the results.

Two modes:

1. **Simple projection** (no aggregates, no GROUP BY): each input record maps 1:1 to an output record with selected/computed columns.
2. **Aggregation mode** (when any expression contains `hasAgg()`): consumes records between `GroupByOperator.MARKER` sentinels, calling `expression.update(record)` for each, then `expression.evaluate()` once to produce the aggregate result.

Schema is built from the expression types, not the source schema. This is why `ProjectOperator` calls the base constructor with no source -- it manually sets `outputSchema` during `initialize()`.

### GroupByOperator

Partitions records by the GROUP BY column values and emits them grouped, separated by a `MARKER` sentinel record.

Implementation:
1. Consumes the entire source iterator eagerly.
2. Hashes each record's group-by values into a `Map<Record, String>` where the value is a temp table name.
3. Inserts each record into the appropriate temp table.
4. Yields records from each temp table in sequence, inserting `MARKER` between groups.

The `MARKER` is a static `new Record()` (empty record) that `ProjectOperator` uses as a group boundary signal. This is the key protocol between `GroupByOperator` and `ProjectOperator` for computing aggregates.

**Cost estimate**: based on external sort cost formula `2 * N * numPasses`, since the grouping materializes all records.

### SortOperator

External merge sort on a single column. Contains TODO stubs for the core algorithm (proj3_part1):

- **`sortRun(Iterator<Record>)`**: sort a chunk of records in memory.
- **`mergeSortedRuns(List<Run>)`**: merge up to `B-1` sorted runs using a priority queue.
- **`mergePass(List<Run>)`**: one pass of merging, producing fewer, larger runs.
- **`sort()`**: orchestrates the full external sort.

Key properties:
- `materialized() = true` -- results are stored in a `Run` (temp table).
- `sortedBy()` reports the sort column, enabling sort-aware optimizations.
- Cost: `2 * N * ceil(1 + log_{B-1}(ceil(N/B)))`.
- Uses `RecordComparator` which compares records on the sort column using `DataBox.compareTo()`.

### LimitOperator

Yields at most `limit` records after skipping `offset` records from the source.

- Constructor's iterator eagerly advances through `offset` records, then yields up to `limit`.
- `estimateIOCost() = 0` (no disk I/O of its own).
- Preserves `sortedBy()` from the source.

### MaterializeOperator

Extends `SequentialScanOperator`. Eagerly consumes its source into a temp table, then acts as a sequential scan over that temp table.

```java
private static String materializeToTable(QueryOperator source, TransactionContext tx) {
    String name = tx.createTempTable(source.getSchema());
    for (Record record : source) tx.addRecord(name, record);
    return name;
}
```

Used by join operators that need to scan the right relation multiple times (SNLJ, BNLJ materialize the right source in their constructors). Preserves `sortedBy()` from the source.

---

## Join Operators

All join operators extend `JoinOperator`, which extends `QueryOperator`.

### JoinOperator.java -- Base Class

- Has two sources: `leftSource` and `rightSource`.
- Overrides `getSource()` to throw (forcing callers to use `getLeftSource()` / `getRightSource()`).
- `computeSchema()` = `leftSchema.concat(rightSchema)`.
- `compare(leftRecord, rightRecord)` compares the join column values.
- `estimateStats()` uses `TableStats.copyWithJoin()`.
- `JoinType` enum: `SNLJ, PNLJ, BNLJ, SORTMERGE, SHJ, GHJ`.

### SNLJOperator -- Simple Nested Loop Join

The simplest and most expensive join. For each record in the left (outer) relation, scans the entire right (inner) relation.

- Right source is materialized in the constructor for backtracking.
- Cost: `|left records| * |right pages| + left IO cost`.
- The right iterator uses `markNext()` / `reset()` to restart from the beginning for each left record.

### PNLJOperator -- Page Nested Loop Join

A subclass of `BNLJOperator` that sets `numBuffers = 3`. This forces the left block size to `3 - 2 = 1` page, making it a page-at-a-time nested loop join.

### BNLJOperator -- Block Nested Loop Join

Reads `B-2` pages of left records into memory at a time, then for each page of right records, checks all combinations.

- Cost: `ceil(|left pages| / (B-2)) * |right pages| + left IO cost`.
- Contains TODO stubs for `fetchNextLeftBlock()`, `fetchNextRightPage()`, and `fetchNextRecord()` (proj3_part1).
- Uses `QueryOperator.getBlockIterator()` to consume pages of records.

### SortMergeOperator

Sorts both inputs on their join columns (if not already sorted), then merges.

```java
private static QueryOperator prepareLeft(tx, leftSource, leftColumn) {
    if (leftSource.sortedBy().contains(leftColumn)) return leftSource;
    return new SortOperator(tx, leftSource, leftColumn);
}
```

The right source must be materialized (for backtracking on duplicate join keys). If already sorted but not materialized, wraps in `MaterializeOperator`.

- `sortedBy()` returns both join column names.
- Contains a TODO stub for `fetchNextRecord()` (proj3_part1).

### SHJOperator -- Simple Hash Join

Single-pass hash join: partitions the left relation into `B-1` hash buckets, then for each bucket builds an in-memory hash table and probes with all right records.

- Throws `IllegalArgumentException` if any partition exceeds `B-2` pages (cannot fit in memory).
- `estimateIOCost() = Integer.MAX_VALUE` -- deliberately high to discourage the optimizer from choosing it (since it can fail).
- Results are accumulated in a `Run` (materialized).

### GHJOperator -- Grace Hash Join

Recursive hash join that handles large inputs by repartitioning.

Algorithm:
1. **Partition**: hash both left and right records into `B-1` partitions using `HashFunc.hashDataBox(value, pass)`.
2. **Build and probe**: for each partition pair, if either side fits in `B-2` pages, build an in-memory hash table on the smaller side and probe with the other.
3. **Recurse**: if neither side fits, recursively call `run()` with `pass + 1` (using a different hash function). Fails after 5 passes.

- Contains TODO stubs for `partition()`, `buildAndProbe()`, and the recursive logic (proj3_part1).
- `estimateIOCost() = Integer.MAX_VALUE` (same reasoning as SHJ).
- Materialized: all results accumulated in `this.joinedRecords` Run.

---

## Disk Utilities: `query/disk/`

### Run.java

A `Run` is a sequence of records stored in a temp table. Used by external sort and hash join to spill records to disk.

- Lazily creates the temp table on first `add()` call.
- `iterator()` returns a `BacktrackingIterator<Record>` over the temp table (or an empty iterator if nothing was added).
- Used by `SortOperator` to store sorted runs between merge passes, and by `GHJOperator`/`SHJOperator` to accumulate join results.

### Partition.java

A `Partition` is similar to a `Run` but creates the temp table eagerly in the constructor. Used by hash join operators to hold records assigned to a hash bucket.

- `getNumPages()` returns the page count, used to decide whether the partition fits in memory for build-and-probe.
- `getScanOperator()` returns a `SequentialScanOperator` over the partition's temp table.

**Key difference**: `Run` is lazy (no temp table until first record), `Partition` is eager (temp table created immediately). This matters because hash join needs to know `getNumPages()` even for empty partitions.

---

## Expression System: `query/expr/`

Expressions represent computations that can be evaluated against records. They form a tree structure and are used by `ProjectOperator` for column selection, arithmetic, and aggregation.

### Expression.java (abstract base)

Core interface:
- `evaluate(Record r)` -- compute the expression's value for a given record, returning a `DataBox`.
- `update(Record r)` -- for aggregate functions, update internal state with a new record.
- `reset()` -- clear aggregate state (for GROUP BY boundaries).
- `getType()` -- return the output data type.
- `setSchema(Schema)` -- bind column references to positions. Propagates to all children.
- `getDependencies()` -- the set of column names this expression reads.
- `hasAgg()` -- whether this expression (or any sub-expression) contains an aggregate function.

**`Expression.fromString(String)`** parses an expression string using the `RookieParser` and `ExpressionVisitor`. This is how SQL-level expressions become operator-level objects.

Includes inner classes for all operator types: `AndExpression`, `OrExpression`, `NotExpression`, `AdditiveExpression`, `MultiplicativeExpression`, `NegateExpression`, and comparison expressions (`<`, `<=`, `>`, `>=`, `=`, `!=`). Arithmetic expressions use `resultType()` for implicit upcasting (INT + FLOAT = FLOAT).

Also provides `toCNF()` to convert to conjunctive normal form, which is useful for pushing predicates down the query plan.

### Column.java

Represents a column reference (e.g., `table1.col`). On `setSchema()`, resolves the column name to an index. `evaluate()` returns `record.getValue(col)`.

### Literal.java

Wraps a constant `DataBox`. `evaluate()` always returns the same value regardless of the input record.

### AggregateFunction.java

Abstract base for aggregate functions. Key constraint: takes exactly one argument and cannot be nested (`SUM(MAX(x))` is rejected).

Concrete implementations: `SUM`, `COUNT`, `MIN`, `MAX`, `AVG`, `VARIANCE`, `STDDEV`, `RANGE`, `FIRST`, `LAST`, `RANDOM`. Each maintains internal state that is updated per-record via `update()` and reset between groups via `reset()`.

The protocol between aggregates and `ProjectOperator`:
1. `GroupByOperator` emits records grouped by key, separated by `MARKER`.
2. `ProjectOperator` calls `expression.update(record)` for each non-marker record in a group.
3. At the group boundary, calls `expression.evaluate()` to get the aggregate result, then `expression.reset()`.

### NamedFunction.java

Non-aggregate scalar functions: `UPPER`, `LOWER`, `REPLACE`, `ROUND`, `CEIL`, `FLOOR`, `NEGATE`. Each takes a fixed number of arguments and evaluates per-record without state.

### ExpressionVisitor.java

AST visitor that walks the parse tree produced by `RookieParser` and builds an `Expression` tree. Handles operator precedence through nested visitors: `OrExpressionVisitor` > `AndExpressionVisitor` > `NotExpressionVisitor` > `ComparisonExpressionVisitor` > `AdditiveExpressionVisitor` > `MultiplicativeExpressionVisitor` > `PrimaryExpressionVisitor`. Function calls are handled by `FunctionCallVisitor`, which dispatches to `Expression.function()`.

---

## QueryPlan.java -- Query Builder and Optimizer

`QueryPlan` uses a builder pattern to collect query components, then constructs the operator DAG on `execute()`.

### Builder methods (called before execute)

| Method | SQL clause | Storage |
|--------|-----------|---------|
| `project(columns)` | SELECT | `projectColumns`, `projectFunctions` |
| `select(col, op, val)` | WHERE | `selectPredicates` (list of `SelectPredicate`) |
| `join(table, leftCol, rightCol)` | INNER JOIN ... ON | `joinPredicates` (list of `JoinPredicate`) |
| `groupBy(columns)` | GROUP BY | `groupByColumns` |
| `sort(column)` | ORDER BY | `sortColumn` |
| `limit(n, offset)` | LIMIT ... OFFSET | `limit`, `offset` |

### Naive execution: `executeNaive()`

Builds operators in this fixed order:

1. **Scan**: `SequentialScanOperator` on the base table (or `IndexScanOperator` if an eligible indexed predicate exists).
2. **Joins**: for each join predicate, `SNLJOperator` with a sequential scan on the right table.
3. **Selects**: stack `SelectOperator` for each WHERE predicate.
4. **Group By**: `GroupByOperator` if GROUP BY columns exist.
5. **Project**: `ProjectOperator` if columns are specified.
6. **Sort**: `SortOperator` if ORDER BY is specified (skipped if already sorted).
7. **Limit**: `LimitOperator` if LIMIT is specified.

### Optimized execution: `execute()` (proj3_part2 TODO)

Implements System R-style cost-based optimization:

- **Pass 1** (`minCostSingleAccess`): for each table, choose between sequential scan and index scan. Push down applicable select predicates. Pick the lowest-cost access path.
- **Pass i** (`minCostJoins`): for each set of already-joined tables, try adding one more table via each applicable join predicate. Choose the cheapest join type via `minCostJoinType` (considers SNLJ and BNLJ by default).
- **Final**: take the minimum-cost plan from the last pass, then add GROUP BY, PROJECT, SORT, LIMIT on top.

Key optimization methods:
- `getEligibleIndexColumns(table)` -- finds select predicates that can use an index (all operators except `!=`).
- `addEligibleSelections(source, except)` -- pushes select predicates down to just above the scan, skipping one predicate (the one already handled by an index scan).
- `minCostJoinType(left, right, leftCol, rightCol)` -- tries SNLJ and BNLJ, returns the one with lower `estimateIOCost()`.

---

## End-to-End Query Flow

Here is how `SELECT t1.x, t2.y FROM t1 INNER JOIN t2 ON t1.id = t2.id WHERE t1.x > 5 ORDER BY t1.x LIMIT 10` executes with the naive plan:

```
LimitOperator (limit=10)
  |
  SortOperator (on t1.x)
    |
    ProjectOperator (t1.x, t2.y)
      |
      SelectOperator (t1.x > 5)
        |
        SNLJOperator (t1.id = t2.id)
          |           |
          SeqScan(t1) SeqScan(t2) [materialized]
```

When `iterator()` is called on the root `LimitOperator`:
1. It calls `source.iterator()` on the `SortOperator`.
2. `SortOperator.iterator()` calls `sort()`, which eagerly consumes the entire sub-tree, performs external merge sort, and returns a `Run` iterator.
3. The sort calls `getSource().iterator()` on `ProjectOperator`, which pulls from `SelectOperator`, which pulls from `SNLJOperator`.
4. `SNLJOperator` iterates over every record in `t1`, and for each, scans all of `t2` (materialized) looking for matches.
5. Matching pairs are concatenated and passed up through Select (filtered), Project (columns picked), Sort (sorted), and Limit (capped at 10).
