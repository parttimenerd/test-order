# test-order Dependency Index Format Specification

Technical specification for `.test-order/test-dependencies.lz4`.

This document describes the current binary data format: format version 1.

## Quick Reference

| Attribute | Value |
|---|---|
| File Name | `test-dependencies.lz4` |
| Container | LZ4 frame (`LZ4FrameOutputStream`) |
| Inner Magic | `TORD` (4 bytes) |
| Format Version | `1` (2-byte signed short, big-endian) |
| Payload Layout | Section-based (`type + length + payload`) |
| String Encoding | UTF-8 (`DataOutputStream.writeUTF` where applicable) |

## Physical Layout

The file is an LZ4 frame stream. The decompressed payload is:

```
[MAGIC: 4 bytes "TORD"]
[FORMAT_VERSION: 2 bytes]
[SECTION_COUNT: 4 bytes]
[SECTION 1]
[SECTION 2]
...
[SECTION N]
```

Each section is encoded as:

```
[SECTION_TYPE: 2 bytes]
[SECTION_LENGTH: 4 bytes]
[SECTION_PAYLOAD: SECTION_LENGTH bytes]
```

Unknown section types are skipped by length, enabling forward-compatible readers.

## Header

### 1. Magic (4 bytes)

- ASCII: `TORD`
- Purpose: identify test-order dependency index payload
- Validation: must match exactly

### 2. Format Version (2 bytes)

- Current: `1`
- Type: signed short (big-endian)
- Reader behavior:
- `1`: supported
- anything else: fail with "Unsupported dependency index format version"

### 3. Section Count (4 bytes)

- Number of sections present in the payload
- Reader sanity limit: `0..100`

## Section Types

| Type | Constant | Description | Required |
|---|---|---|---|
| `1` | `SECTION_TRIE` | Radix trie dictionary of class names | Yes |
| `2` | `SECTION_TEST_CLASSES` | Ordered list of test class IDs | Yes |
| `3` | `SECTION_DEP_GROUPS` | Row-deduplicated class dependency groups | Yes |
| `4` | `SECTION_METHOD_DEPS` | Per-method class dependencies | No |
| `5` | `SECTION_MEMBER_DEPS` | Per-test-class member-level dependencies | No |
| `6` | `SECTION_METHOD_MEMBER_DEPS` | Per-test-method member-level dependencies | No |

Required-order constraints in the reader:

- `SECTION_TRIE` must be available before `SECTION_TEST_CLASSES`
- `SECTION_TRIE` and `SECTION_TEST_CLASSES` must be available before `SECTION_DEP_GROUPS`

## Section Payloads

### SECTION_TRIE (1)

- Payload: serialized `ClassNameTrie`
- Purpose: map class IDs to class names for compact bitmap encoding

### SECTION_TEST_CLASSES (2)

```
[TEST_COUNT: int]
repeat TEST_COUNT times:
  [TEST_CLASS_ID: int]
```

- IDs resolve through the trie
- Order is preserved and used for deterministic ordering

### SECTION_DEP_GROUPS (3)

```
[GROUP_COUNT: int]
repeat GROUP_COUNT times:
  [DEP_BITMAP_SIZE: int]
  [DEP_BITMAP_BYTES]
  [MEMBER_BITMAP_SIZE: int]
  [MEMBER_BITMAP_BYTES]
```

- Bitmaps use RoaringBitmap serialization
- `DEP_BITMAP`: dependency class IDs (via trie)
- `MEMBER_BITMAP`: indices into `SECTION_TEST_CLASSES`
- Multiple tests may share identical dep sets (row deduplication)

### SECTION_METHOD_DEPS (4)

```
[METHOD_COUNT: int]
repeat METHOD_COUNT times:
  [METHOD_KEY: UTF]
  [DEP_BITMAP_SIZE: int]
  [DEP_BITMAP_BYTES]
```

- `METHOD_KEY` format: `className#methodName`

### SECTION_MEMBER_DEPS (5)

```
[ENTRY_COUNT: int]
repeat ENTRY_COUNT times:
  [TEST_CLASS: UTF]
  [MEMBER_COUNT: int]
  repeat MEMBER_COUNT times:
    [MEMBER_KEY: UTF]
```

- `MEMBER_KEY` format: `depClass#member`

### SECTION_METHOD_MEMBER_DEPS (6)

```
[ENTRY_COUNT: int]
repeat ENTRY_COUNT times:
  [METHOD_KEY: UTF]
  [MEMBER_COUNT: int]
  repeat MEMBER_COUNT times:
    [MEMBER_KEY: UTF]
```

- `METHOD_KEY` format: `className#methodName`
- `MEMBER_KEY` format: `depClass#member`

## Validation and Limits

Reader-side safety checks:

- LZ4 frame magic validation before payload decode
- Compressed file size cap: `1_000_000_000` bytes
- Section count cap: `100`
- Generic block size cap for payload chunks and bitmaps: `64 MiB`
- Entry count cap (`testCount`, `groupCount`, etc.): `1_000_000`

On validation failure, loading fails with `IOException`.

## Forward Compatibility Rules

1. New fields should be introduced via new section types whenever possible.
2. Existing section payloads should remain stable for the same version.
3. Readers must skip unknown section types using `SECTION_LENGTH`.
4. Any incompatible change requires bumping `FORMAT_VERSION`.

## Notes

- This specification is the authoritative reference for dependency index persistence.
- The state/history file (`.test-order/state.lz4`) is a separate format with its own schema versioning.
