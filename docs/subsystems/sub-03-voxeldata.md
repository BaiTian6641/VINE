# SUB-03 — VoxelData persistence

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M1 · **Depends on:** SUB-01, SUB-02 · **Blocks:** SUB-04, SUB-07, SUB-08, SUB-13, SUB-14
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.4 (+ §5.1, §5.12, §7, §9 perf row) · **Module(s):** vine-api, vine-core, vine-spi, drivers

Source of truth for `VoxelData` (README shared vocabulary): other files
reference it, never redefine it.

## 1. Purpose

`VoxelData` is VINE's engine-owned, self-describing data tree — the single
portable carrier for consumer state (item data, entity stats, session state).
Every cell is post-1.20.5, so portable-strategy fields ride one engine-owned
Data Component (`vine:voxel_data`) on all four cells with identical
semantics, while native-strategy fields map to native components/NBT (§5.4);
schemas are engine-versioned with engine-side datafixers, decoupling consumer
migration from Mojang's DFU data-version churn. Non-goals: not a DFU replacement for
vanilla content; not a foreign-mod NBT editor (native strategy covers
interop); not an ECS.

## 2. Design

**Type system** — NBT-shaped, closed: `BYTE SHORT INT LONG FLOAT DOUBLE STRING
BYTE_ARRAY INT_ARRAY LONG_ARRAY LIST COMPOUND`. Dot-paths (`"stats.mana"`)
address nested keys; lists are homogeneous (element type fixed on first
insert). `VineId` (sub-02) stores as string.

**API surface** (`vine-api`, `dev.vineengine.vine.data`):

```java
public interface VoxelData {                  // mutable compound root
    boolean contains(String path);
    @Nullable VoxelType typeOf(String path);
    int getInt(String path);                  // missing → 0/""/empty; one get+put per VoxelType
    VoxelList getList(String path);
    VoxelData getCompound(String path);       // live child; mutations propagate
    void put(String path, int value);
    void remove(String path);
    VoxelView snapshot();                     // zero-copy read view
    int schemaVersion();
    void addChangeListener(VoxelSyncListener listener);
}
public record VoxelSchema(VineId id, int version, Codec<VoxelData> codec) {}
public interface VoxelDataFixer { VoxelData fix(VoxelData data, int fromVersion); }
public final class VineData {
    static void registerSchema(VoxelSchema s, List<VoxelDataFixer> fixers);
    static VoxelData create(VineId schemaId);
    static VoxelData of(VoxelTarget target, VineId schemaId);   // attach-point access
}
public sealed interface VoxelTarget permits ItemStackTarget, BlockEntityTarget,
        EntityTarget, PlayerTarget, WorldTarget {}
public sealed interface FieldStrategy {       // per-field, opt-in native interop
    record Portable() implements FieldStrategy {}                        // default
    record Native(String nativeComponentId) implements FieldStrategy {}  // "minecraft:damage"
}
@Target(METHOD) @interface FastPath { String justification(); } // raw hooks, benchmark-gated
```

`Codec` = `com.mojang.serialization.Codec` (DFU the standalone library,
Prime-Invariant-safe, same call as sub-02 descriptors). What we refuse is
Mojang's data *versions*: fixers are plain functions, never Mojang
`DataFixer`/`Schema` types.

**Internals** (`vine-core`, `internal.data`): insertion-ordered key maps;
interned path segments; per-path cached child resolution. Wire/save format:
NBT-compatible tag stream plus engine header (magic, schema id, schema
version); the payload is a plain `CompoundTag` on every cell, so vanilla
save/load and BE/item sync carry it unchanged — the header routes fixing.
Schema registry `VineId → (version, fixer chain)`; fixers compose `vN→vN+1`,
applied lazily at load; a newer-than-registered blob opens read-only with a
warning, never destructive. Sync: dirty path-set (mutation marks path +
ancestors); whole-tree sync only for initial/late-join (transport = sub-05).

**Driver contract** (`vine-spi`):

```java
public interface VoxelStorageDriver {
    VoxelData open(VoxelTarget target, VineId schemaId);
    void flushDirty(VoxelTarget target, VineId schemaId, Set<String> dirtyPaths);
}
```

- **Registration timing**: 1.21.1-NF — `DeferredRegister<DataComponentType<?>>`
  at sub-01's `REGISTRIES_OPEN`; 1.21.1-Fabric — `Registry.register(
  Registries.DATA_COMPONENT_TYPE, …)` in mod init; 26.x — same concept,
  drivers absorb drift (M4, with sub-19). All cells assert the
  `hasDataComponents` probe (§5.12) at boot; absence = clean boot failure.
- **Attach points**: item stacks = component get/set; block entities =
  reserved `vine` sub-compound in the BE save tag (driver hook/Mixin, stripped
  from vanilla sync unless the field is client-visible); entities + players =
  NF data attachments / Fabric custom-data Mixin under the `vine` key; world =
  SavedData `vine_<ns>`.
- **Client-visible data**: flagged per field at schema registration; only
  those paths enter BE/entity sync; deltas via sub-05.

## 3. Stages

### Stage A — core tree & codec

- [x] **Do:** `VoxelType`, node model, `VoxelData`/`VoxelList`/`VoxelView` impl
  in `internal.data`; binary codec + engine header; deep-equal/copy. Pure JVM,
  zero MC imports.
- **Acceptance:** vine-core unit: 100k randomized-tree round-trips
  byte-stable; `snapshot()` reflects live mutations, zero copy.
- **Touches:** vine-core.
- **Bootstrap prompt:**
  > Build the VoxelData core per docs/subsystems/sub-03-voxeldata.md §2 (type
  > system, internals) in vine-core `dev.vineengine.vine.internal.data`, no MC
  > imports, conventions per docs/README.md (Java 21). Acceptance: Stage-A
  > suite above. vine-api comes in Stage C — do not touch it.

### Stage B — schema versioning & engine datafixers

- [x] **Do:** `VoxelSchema`, `VoxelDataFixer`, schema registry, lazy
  fix-on-load, newer-version read-only guard.
- **Acceptance:** unit: v1 fixture blob + two chained fixers reads as current;
  newer-version blob opens read-only, mutations never persist.
- **Touches:** vine-core.
- **Bootstrap prompt:**
  > Extend Stage A with schema versioning per sub-03 §2 internals; fixers are
  > plain VoxelData→VoxelData functions chained vN→vN+1, no Mojang
  > DataFixer/Schema types. Acceptance: Stage-B checks above.

### Stage C — API surface + SPI

- [ ] **Do:** public API per §2 into vine-api; `VoxelStorageDriver` SPI into
  vine-spi; testmod schema with one portable + one native field.
- **Acceptance:** compiles (Java 21); testmod (sub-22) registers a schema and
  creates a tree in-memory.
- **Touches:** vine-api, vine-spi, vine-testmod.
- **Bootstrap prompt:**
  > Publish the API exactly as sketched in sub-03 §2 into vine-api, SPI into
  > vine-spi. Prime Invariant: no net.minecraft/loader types in signatures
  > (com.mojang.serialization.Codec is allowed). Acceptance: compiles;
  > testmod registers + creates.

### Stage D — 1.21.1 drivers (NF ∥ Fabric — the two cells may run in parallel)

- [ ] **Do:** component registration at `REGISTRIES_OPEN`; item-stack +
  world attach; full `VoxelStorageDriver` per cell; `hasDataComponents` assert.
- **Acceptance:** TCK `voxeldata.roundtrip` + `voxeldata.native_strategy`
  green on both 1.21.1 cells (§8: one per loader family).
- **Touches:** driver-1.21.1-neoforge, driver-1.21.1-fabric.
- **Bootstrap prompt:**
  > Implement `VoxelStorageDriver` for your cell (1.21.1-NF or 1.21.1-Fabric)
  > per sub-03 §2 driver contract. Mixins only inside the driver (§5.9).
  > Acceptance: the two named TCK scenarios pass on your cell.

### Stage E — remaining attach points + sync hooks

- [ ] **Do:** BE/entity/player attach (NF attachments; Fabric custom-data
  Mixin); dirty path-set tracking; change listeners; client-visible filtering.
- **Acceptance:** TCK `voxeldata.attach_all` + `voxeldata.sync_delta` green on
  both 1.21.1 cells; measured delta payload < whole-tree payload.
- **Touches:** vine-core (sync), both 1.21.1 drivers, vine-tck.
- **Bootstrap prompt:**
  > Complete attach points and sync per sub-03 §2; depends on Stage D of your
  > cell. Acceptance: the two named TCK scenarios pass on both 1.21.1 cells.

### Stage F — golden fixtures, perf budget, 26.x readiness

- [ ] **Do:** pin golden saves from each 1.21.1 driver; cross-read harness;
  TCK `voxeldata.perf`; `@FastPath` hooks only where the benchmark proves a
  hotspot; 26.x notes for sub-19 (26.x executes in M4).
- **Acceptance:** fixtures cross-read byte-identical on all available cells;
  §5 perf budget met on both 1.21.1 cells.
- **Touches:** vine-tck, vine-core.
- **Bootstrap prompt:**
  > Own golden-fixture round-trip and the perf budget per sub-03 §5: fixtures
  > from both 1.21.1 drivers, cross-read, perf benchmark, 26.x driver notes.
  > Acceptance: byte-identical cross-reads; budget numbers in the TCK report.

## 4. Problems & blockers

- **Component registration timing differs per loader** (NF mod-bus phase vs
  Fabric static init vs 26.x drift) — sub-01's normalized `REGISTRIES_OPEN`
  phase; the SPI forces registration there; acceptance = boot log + round-trip.
- **Change-sync granularity: dirty flags vs whole-tree** — decided:
  path-granular dirty sets with ancestor coarsening; whole-tree only for
  initial/late-join. Revisit only if `voxeldata.perf` regresses.
- **DFU version skew between cells** (1.21.1 vs 26.x data versions diverge) —
  engine header + engine fixers; Mojang DFU never touches `vine:voxel_data`;
  golden fixtures are the standing proof.
- **Perf budget on tick-hot paths** — measurable budget in §5; `@FastPath`
  raw hooks require benchmark justification in the commit message. Decision
  owner: this file.
- **Vanilla rewriting native-strategy fields** (anvil/grindstone touching
  `minecraft:damage`) — documented interop semantics, not corruption; native
  fields re-read on access, never cached.

## 5. Verification

TCK scenarios owned (each green on ≥2 drivers, one per loader family — §8):
`voxeldata.roundtrip` (write → save → reload → deep-equal) ·
`voxeldata.schemafix` (v1 fixture + fixer chain reads current) ·
`voxeldata.native_strategy` (`minecraft:damage` visible to vanilla anvil) ·
`voxeldata.attach_all` (all five attach points survive save/load) ·
`voxeldata.sync_delta` (dirty delta < whole-tree bytes).
Golden fixtures: `vine-tck/fixtures/save-1.21.1/` + `save-26x/`; every driver
reads both; loaded trees byte-identical (§7).
**Perf budget** (`voxeldata.perf`, all cells): primitive get on a 3-level path
≤ 250 ns/op; zero allocation on primitive read hits; 64-field compound
serialize ≤ 15 µs; 10k mixed reads in one tick ≤ 1 ms; dirty tracking ≤ 5%
overhead on a 1k-mutation loop.

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.data` (public) /
  `dev.vineengine.vine.internal.data` (core/SPI/drivers); shared modules Java
  21 bytecode; 26.x drivers may use Java 25.
- **Comment policy:** javadoc states *why* and invariants (list homogeneity,
  read-only-on-newer-schema); driver comments name the per-cell
  registration/attach difference absorbed.
- **Forbidden:** version-string parsing (§5.12); Mojang `DataFixer`/`Schema`
  types; loader classes in API signatures; Mixins outside drivers; redefining
  `VoxelData` in another subsystem file.
- **Done means:** all stage boxes ticked; scenarios, fixtures, and perf green
  on all covered cells; status header `done`; dashboard row updated in
  `docs/README.md`.
