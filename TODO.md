# MRC Phase 4 — TODO

## P4-1: Fix the OpIdMap gap  ← start here
Register all `ExtendedOperatorLibrary` operator types in `OpIdMap`:
- `RotateLeft`, `RotateRight`, `BitReverse`, `NibbleSwap`, `DeltaOfDelta` (FunctionOperator subtypes)
- `CompositeOperator`
- `SuperfunctionOperator` subtypes: `Iterated`, `FixedPointReach`, `Conjugate`

Remove the workaround filter in `GaServiceMain.publishSnapshot()`:
```java
// DELETE this block once OpIdMap is fixed:
List<Chromosome.OperatorRule> safeRules = best.rules().stream()
    .filter(r -> { try { mrc.core.OpIdMap.getOpId(r.assignedOperator()); return true; }
                   catch (IllegalArgumentException e) { return false; } })
    .toList();
```

Files to touch:
- `mrc-core/src/main/java/mrc/core/OpIdMap.java` — add extended opId entries
- `mrc-core/src/main/java/mrc/core/extended/ExtendedOperatorLibrary.java` — verify opIds match
- `mrc-ga-service/src/main/java/mrc/gaservice/GaServiceMain.java` — remove filter
- Tests: `mrc-snapshot-db` and `mrc-ga-service` tests should pass without seeded Random workarounds

---

## P4-2: Streaming encode/decode API
Add `StreamEncoder` / `StreamDecoder` working with `InputStream` / `OutputStream` in chunks.
- Required for large files, network pipes, real-time use
- Chunk boundaries must not break ARITH_RUN tokens (carry state across chunks)
- New classes in `mrc-codec`: `mrc.codec.StreamEncoder`, `mrc.codec.StreamDecoder`

---

## P4-3: Snapshot metadata enrichment
Store `DataFingerprint` of training corpus inside snapshot at publish time.
- `SnapshotSerializer` writes fingerprint into manifest
- `SnapshotRanker` reads fingerprint directly — replaces keyword heuristics with real data
- Makes selector fully data-driven

---

## P4-4: Unified `mrc` CLI tool
```
mrc encode  --snapshot <id> --store <path> input.bin output.mrc3
mrc decode  --store <path> input.mrc3 output.bin
mrc inspect input.mrc3          # prints header, snapshot_id, ratio
mrc serve   --store <path> --ga-port 8080 --selector-port 8081
mrc bench   --store <path> input.bin    # compare v0x01/v0x02/v0x03
```
New module: `mrc-cli` depending on `mrc-codec-v3`, `mrc-selector`, `mrc-ga-service`.

---

## P4-5: Remote snapshot store (HTTP-backed)
`HttpSnapshotStore` — implements `SnapshotStore`, fetches via `GET /snapshot/{id}`.
- No local filesystem copy needed on codec nodes
- Pairs with a simple static file server or CDN in front of `FileSnapshotStore`
- New class in `mrc-snapshot-db`: `mrc.snapshotdb.HttpSnapshotStore`

---

## State at end of Phase 3 (all green)
- 193 tests, 0 failures across 9 modules
- Commit: `eeb80c4` — "Implement Phase 3: distributed adaptive compression platform"
- All docs refreshed: README, ARCHITECTURE, USAGE_GUIDE, THEORY
