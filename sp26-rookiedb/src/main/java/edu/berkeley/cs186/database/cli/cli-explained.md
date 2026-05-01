# cli/ -- The SQL Interface

This module implements the user-facing SQL interface: a REPL (CommandLineInterface), a multi-client server, output formatting, a JavaCC-generated parser, and a visitor layer that translates parse trees into database operations.

## High-Level Data Flow

```
SQL string
    |
    v
RookieParser (JavaCC-generated)     -- lexer + parser
    |
    v
AST (tree of SimpleNode subclasses) -- e.g., ASTSelectStatement -> ASTFromClause -> ...
    |
    v
Visitor layer                       -- StatementListVisitor dispatches to per-statement visitors
    |
    v
Database API calls                  -- transaction.query(), transaction.insert(), etc.
    |
    v
PrettyPrinter                       -- formats result records into columnar text
```

## CommandLineInterface.java -- The REPL

`CommandLineInterface` is the main entry point (has `public static void main`). It creates a `Database`, calls `db.loadDemo()` to populate sample data, then runs the REPL loop.

### Input Handling

`bufferUserInput(Scanner)` accumulates lines until a semicolon-terminated statement is complete. It tracks single-quote parity for multi-line string literals and shows different prompts:
- `=> ` -- ready for input
- `-> ` -- continuation line (no semicolon yet)
- `'> ` -- inside an unclosed string literal

### Statement Execution Pipeline

1. Input bytes are fed to `new RookieParser(stream)`.
2. `parser.sql_stmt_list()` returns an `ASTSQLStatementList` node.
3. A `StatementListVisitor` accepts the AST and collects `StatementVisitor` instances.
4. `visitor.execute(currTransaction)` runs each statement, managing the transaction lifecycle.

### Meta-Commands

Lines starting with `\` are meta-commands (not SQL):
- `\d` -- list all tables (scans table metadata).
- `\d tableName` -- show schema of a specific table.
- `\di` -- list all indexes.
- `\locks` -- show locks held by the current transaction.

### Transaction Management

The REPL maintains a `currTransaction` reference. Without an explicit `BEGIN`, each statement auto-commits in its own transaction. With `BEGIN`, statements share a transaction until `COMMIT` or `ROLLBACK`. If the user disconnects with an open transaction, it is rolled back.

## Server.java -- Multi-Client TCP Server

Listens on port 18600 (configurable). Each client connection spawns a `ClientThread` that creates a `CommandLineInterface` with the socket's I/O streams. This allows multiple concurrent REPL sessions sharing the same `Database` instance--primarily used to demonstrate that the locking system (Project 4) works correctly.

**Security note** (from the source): there is no authentication. This is a teaching tool, not a production server.

## PrettyPrinter.java -- Output Formatting

Formats query results as aligned ASCII tables with `|` column separators and `-`/`+` dividers:

```
 name       | age
------------+-----
 Alice      |  30
 Bob        |  25
(2 rows)
```

Key behaviors:
- Column widths auto-size to the widest value (or header).
- Integer and long values are right-aligned within their columns.
- Null bytes in strings are stripped before display.
- Row count is printed at the bottom ("(N rows)" or "(1 row)").

`PrettyPrinter.parseLiteral(String)` converts SQL literal tokens to `DataBox` values:
- `'text'` -> `StringDataBox`
- `true`/`false` -> `BoolDataBox`
- Numbers with `.` -> `FloatDataBox`
- Other numbers -> `IntDataBox`

This method is used by `InsertStatementVisitor` and `ColumnValueComparisonVisitor` to convert parsed literal tokens into typed values.

## parser/ -- JavaCC-Generated Parser

All files in this directory are auto-generated from `RookieParser.jjt` (located at the project root). **Do not edit these files manually.**

### Key Generated Classes

- **RookieParser** -- the parser itself. Entry point: `parser.sql_stmt_list()` returns an `ASTSQLStatementList`.
- **RookieParserTokenManager** -- the lexer, recognizes tokens defined in the grammar.
- **RookieParserConstants** -- token IDs (e.g., `K_SELECT = 35`, `K_FROM = 36`) and their string images. The grammar supports SQL keywords (SELECT, FROM, WHERE, JOIN, GROUP BY, ORDER, LIMIT, CREATE, DROP, INSERT, UPDATE, DELETE, BEGIN, COMMIT, ROLLBACK, SAVEPOINT, RELEASE, EXPLAIN, WITH, AS), comparison operators, arithmetic operators, boolean operators (AND, OR, NOT), and literals (numeric, string, true/false, identifiers).

### AST Node Classes

Each grammar production generates an `AST*` class extending `SimpleNode`. There are ~45 of them. They fall into categories:

**Statement-level:** `ASTSQLStatementList`, `ASTSelectStatement`, `ASTCreateTableStatement`, `ASTInsertStatement`, `ASTDeleteStatement`, `ASTUpdateStatement`, `ASTBeginStatement`, `ASTCommitStatement`, `ASTRollbackStatement`, `ASTExplainStatement`, etc.

**Clause-level:** `ASTSelectClause`, `ASTFromClause`, `ASTLimitClause`, `ASTOrderClause`, `ASTJoinedTable`, `ASTCommonTableExpression`.

**Expression-level:** `ASTExpression`, `ASTOrExpression`, `ASTAndExpression`, `ASTNotExpression`, `ASTComparisonExpression`, `ASTAdditiveExpression`, `ASTMultiplicativeExpression`, `ASTFunctionCallExpression`, `ASTPrimaryExpression`.

**Leaf-level:** `ASTIdentifier`, `ASTColumnName`, `ASTLiteral`, `ASTNumericLiteral`, `ASTComparisonOperator`.

### Visitor Pattern (Parser Side)

`SimpleNode.jjtAccept(RookieParserVisitor, Object)` calls the typed `visit()` method on the visitor. `SimpleNode.childrenAccept(visitor, data)` recurses into children.

`RookieParserVisitor` is the interface with a `visit` overload for every AST node type. `RookieParserDefaultVisitor` implements all `visit` methods by calling `defaultVisit`, which simply recurses into children. Concrete visitors override only the node types they care about, relying on default recursion for everything else.

## visitor/ -- AST-to-Operation Translation

### StatementType (enum)

Enumerates all SQL statement kinds: `CREATE_TABLE`, `CREATE_INDEX`, `DROP_TABLE`, `DROP_INDEX`, `SELECT`, `INSERT`, `DELETE`, `UPDATE`, `BEGIN`, `COMMIT`, `ROLLBACK`, `SAVEPOINT`, `RELEASE_SAVEPOINT`, `EXPLAIN`.

### StatementVisitor (abstract base)

Extends `RookieParserDefaultVisitor`. Each SQL statement type has a concrete subclass. The base provides:
- `execute(Transaction, PrintStream)` -- runs the statement (default throws).
- `getType()` -- returns the `StatementType` (abstract).
- `getSavepointName()` -- for ROLLBACK TO SAVEPOINT (default empty).
- `getQueryPlan(Transaction)` -- for SELECT/EXPLAIN (default empty).

### StatementListVisitor -- The Top-Level Dispatcher

Accepts an `ASTSQLStatementList` (which may contain multiple semicolon-separated statements). For each child statement node, it creates the appropriate `StatementVisitor` subclass and adds it to a list. The `execute(Transaction)` method then iterates the list:

- **BEGIN/COMMIT/ROLLBACK** are handled inline (they manage the transaction lifecycle).
- **All other statements** delegate to `visitor.execute(transaction, out)`. If there's no active transaction, a temporary auto-commit transaction is created.

### Statement Visitors -- One Per SQL Statement

**SelectStatementVisitor** -- the most complex visitor. It collects:
- Table names and aliases (FROM clause, JOIN clauses)
- Join conditions (left/right column names from ON clauses)
- WHERE predicates (column, operator, value triples)
- SELECT columns, aliases, and expression functions
- GROUP BY columns, ORDER BY column, LIMIT
- WITH clauses (common table expressions)

In `getQueryPlan()`, it builds a `QueryPlan` by calling `transaction.query()`, then `.join()`, `.select()`, `.project()`, `.groupBy()`, `.sort()`, `.limit()` in sequence. Wildcard expansion (`*`, `table.*`) happens here by looking up schemas.

**CreateTableStatementVisitor** -- collects column definitions (name + type) into a `Schema`. Also supports `CREATE TABLE ... AS SELECT ...` by executing the select query and inserting results. The type parsing handles: int/integer, char/varchar/string(n), float, long, bool/boolean.

**InsertStatementVisitor** -- collects rows of literal values and calls `transaction.insert()` for each.

**DeleteStatementVisitor** -- parses the WHERE expression and calls `transaction.delete(tableName, predicate)`.

**UpdateStatementVisitor** -- parses SET column = expression and optional WHERE. Calls `transaction.update()` with both an update function and a filter predicate.

**ExplainStatementVisitor** -- wraps a `SelectStatementVisitor`, calls `getQueryPlan()` and `execute()`, then prints the query plan tree (not the results).

**Transaction control visitors** (`BeginStatementVisitor`, `CommitStatementVisitor`, `RollbackStatementVisitor`, `SavepointStatementVisitor`, `ReleaseStatementVisitor`) -- purely symbolic or carry a savepoint name. The actual transaction operations happen in `StatementListVisitor.execute()`.

**DDL visitors** (`CreateIndexStatementVisitor`, `DropTableStatementVisitor`, `DropIndexStatementVisitor`) -- simple visitors that extract identifiers and call the corresponding `transaction.*` method.

### ExecutableStatementVisitor

An alternative entry point that wraps a single statement. Used by `CommonTableExpressionVisitor` to execute subqueries within WITH clauses. Returns an `Optional<QueryPlan>` if the statement is a SELECT, otherwise executes directly.

### CommonTableExpressionVisitor -- WITH Clause Support

Handles `WITH name AS (SELECT ...)` by:
1. `createTable()` -- executes the subquery, creates a temp table with the result schema.
2. `populateTable()` -- re-executes the subquery and inserts results into the temp table.

The temp table alias is registered with the outer `QueryPlan` via `addTempTableAlias()` so subsequent FROM/JOIN references resolve correctly. Column renaming (`WITH name(col1, col2) AS ...`) is also supported.

### ColumnValueComparisonVisitor

Helper visitor for WHERE clause predicates. Extracts a column name, comparison operator, and literal value from `ASTColumnValueComparison` nodes. Handles operand order normalization: if the literal appears on the left (`5 > col`), it reverses the operator to produce `col < 5` using `PredicateOperator.reverse()`.
