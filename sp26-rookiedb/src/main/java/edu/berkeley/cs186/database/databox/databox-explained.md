# databox/ -- The RookieDB Type System

This module defines the primitive value types used throughout the database: in records, B+ tree keys, index lookups, and on-disk serialization.

## Architecture Overview

```
         TypeId (enum)              Type (descriptor)
         BOOL | INT | FLOAT         = TypeId + sizeInBytes
         STRING | LONG |            e.g. Type.stringType(32)
         BYTE_ARRAY

                    DataBox (abstract, Comparable<DataBox>)
                   /      |       |        |        \        \
            BoolDataBox  IntDB  FloatDB  LongDB  StringDB  ByteArrayDB
```

`Type` describes a column's type (metadata stored in schemas and catalogs). `DataBox` holds an actual value of that type (one cell in one row). The two are connected: every `DataBox` returns its `Type` via `type()`, and every deserialization path requires a `Type` to know what to read.

## TypeId and Type

**TypeId** is a simple enum: `BOOL, INT, FLOAT, STRING, LONG, BYTE_ARRAY`. Its only interesting method is `fromInt(int ordinal)`, used during deserialization.

**Type** pairs a `TypeId` with a byte size. Fixed-size types have exactly one valid `Type` instance each; variable-width types (STRING, BYTE_ARRAY) carry their length:

| Factory method       | TypeId      | sizeInBytes           |
|----------------------|-------------|-----------------------|
| `Type.boolType()`    | BOOL        | 1                     |
| `Type.intType()`     | INT         | 4 (`Integer.BYTES`)   |
| `Type.floatType()`   | FLOAT       | 4 (`Float.BYTES`)     |
| `Type.longType()`    | LONG        | 8 (`Long.BYTES`)      |
| `Type.stringType(n)` | STRING      | n (must be >= 1)      |
| `Type.byteArrayType(n)` | BYTE_ARRAY | n                  |

**Key design point:** `Type.stringType(5)` and `Type.stringType(10)` are considered _different types_. `Type.equals()` checks both `typeId` and `sizeInBytes`. This matters because strings are fixed-width on disk--a `VARCHAR(10)` column always occupies 10 bytes per row.

### Type serialization

`Type.toBytes()` writes two 4-byte ints: the `TypeId` ordinal followed by `sizeInBytes` (8 bytes total). `Type.fromBytes(Buffer)` reverses this. This is used when persisting schema metadata, not when serializing individual values.

### Type.fromString()

Parses SQL-style type names for CREATE TABLE: `"int"`, `"integer"`, `"varchar(32)"`, `"char(10)"`, `"string(5)"`, `"float"`, `"long"`, `"bool"`, `"boolean"`. The parenthesized length is required for string types and rejected for others.

## DataBox -- Abstract Base

`DataBox` is abstract and implements `Comparable<DataBox>`. It provides:

- **type() / getTypeId()** -- abstract, implemented by each subclass.
- **Accessor methods** (`getBool()`, `getInt()`, `getFloat()`, `getString()`, `getLong()`, `getByteArray()`) -- each throws `RuntimeException` by default. Only the matching subclass overrides the relevant one. This is a _tagged union_ pattern: callers switch on `getTypeId()` and then call the correct accessor.
- **toBytes()** -- abstract. Serializes the _value only_ with no type tag.
- **hashBytes()** -- same as `toBytes()` for all types except `StringDataBox` (see below).

### DataBox.fromBytes(Buffer, Type)

The central deserialization entry point. Because serialized DataBoxes carry **no self-describing type tag**, you must provide the `Type` to know how many bytes to consume:

```java
DataBox.fromBytes(buf, Type.intType())    // reads 4 bytes as int
DataBox.fromBytes(buf, Type.stringType(5)) // reads 5 bytes as string
```

This is a deliberate trade-off: records are more compact on disk (no per-field type overhead), but the schema must be known at read time.

### DataBox.fromObject(Object)

Convenience factory for Java objects. Accepts `Integer`, `String`, `Boolean`, `Long`, `Float`, `Double` (narrowed to float), `byte[]`, and passthrough `DataBox`. Used in tests and `QueryPlan` methods to make record construction readable:

```java
new Record(DataBox.fromObject(1), DataBox.fromObject("hello"))
```

### DataBox.fromString(Type, String)

Parses a string representation into a DataBox of the given type. Used by the CLI parser to convert literal tokens.

## Subclass Details

### BoolDataBox
- Wraps a `boolean`. Serialized as a single byte: `0x00` for false, `0x01` for true.
- `compareTo` only accepts another `BoolDataBox`.

### IntDataBox
- Wraps an `int`. Serialized as 4 bytes via `ByteBuffer.putInt()` (big-endian).
- **Cross-type comparison:** `compareTo` accepts `LongDataBox` and `FloatDataBox` in addition to `IntDataBox`, performing widening comparison. This allows mixed-type predicates like `WHERE int_col > 3.5`.

### FloatDataBox
- Wraps a `float`. Serialized as 4 bytes via `ByteBuffer.putFloat()`.
- **Cross-type comparison:** also accepts `IntDataBox` and `LongDataBox`.

### LongDataBox
- Wraps a `long`. Serialized as 8 bytes via `ByteBuffer.putLong()`.
- **Cross-type comparison:** also accepts `IntDataBox` and `FloatDataBox`.

The numeric types (Int, Float, Long) form a mutually-comparable group. Bool and String are only comparable within their own type.

### StringDataBox
- Wraps a `String` with a fixed byte width `m`.
- **Constructor behavior:** `StringDataBox("hello", 3)` truncates to `"hel"`. `StringDataBox("hi", 5)` stores `"hi"` but serializes to 5 bytes padded with null bytes. Trailing null bytes are stripped on construction (`replaceAll("\0*$", "")`).
- **toBytes():** pads the string to exactly `m` bytes with null characters, then encodes as ASCII. This means on-disk strings are always `m` bytes regardless of actual content length.
- **hashBytes():** returns the _unpadded_ string bytes. This is critical for hash joins: `StringDataBox("hi", 5)` and `StringDataBox("hi", 10)` have different `toBytes()` (different padding) but identical `hashBytes()`, so they hash to the same bucket. Without this, equi-joins on string columns with different declared widths would silently fail.
- **equals():** compares only the string content, ignoring `m`. So `new StringDataBox("hi", 5).equals(new StringDataBox("hi", 10))` is `true`.
- **compareTo:** delegates to `String.compareTo` on the unpadded content.

### ByteArrayDataBox
- Wraps a raw `byte[]`. Used internally (e.g., for opaque page data), not exposed as a SQL type.
- **compareTo throws `RuntimeException`** -- byte arrays have no natural ordering, so they cannot be used as B+ tree keys.
- No `equals` or `hashCode` override (uses Object identity).

## Comparable and B+ Tree Keys

`DataBox implements Comparable<DataBox>` is what allows B+ trees to order their keys. Each subclass's `compareTo`:
1. Checks the type of the argument (with numeric cross-type support for Int/Float/Long).
2. Throws `IllegalArgumentException` for incompatible types (e.g., comparing a string to an int).
3. Delegates to the corresponding Java `compare` method (`Integer.compare`, `Float.compare`, etc.).

This means a B+ tree index on a `FLOAT` column can handle lookups with `IntDataBox` keys, since `FloatDataBox.compareTo(IntDataBox)` works correctly.
