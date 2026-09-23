# SUB-02 — Registry & descriptors

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 minimal → M1 full · **Depends on:** SUB-00, SUB-01 · **Blocks:** SUB-04, SUB-07, SUB-11, SUB-12, SUB-13, SUB-15, SUB-20, SUB-22
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.2 (+ §5.3, §5.16) · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

Owns VINE's identity and registration layer: `VineId`, the **descriptor**
machinery every content subsystem builds on, the engine-owned persistent ID
map, and the content pipeline cooking per-cell runtime assets. Descriptors are
pure data, authored from **Java or datapack-style JSON** (locked, §3); drivers
materialize natives at the correct per-cell phase. Non-goals: concrete content
types (sub-07+), event bus (sub-01), `VoxelData` internals (sub-03), scripting.

## 2. Design

**API surface** (`dev.vineengine.vine.registry`, vine-api) — source of truth
for `VineId` and descriptor mechanics; other files reference, never redefine:

```java
public record VineId(String namespace, String path) implements Comparable<VineId> {
    public static VineId of(String namespace, String path); // validated [a-z0-9_.-] / [a-z0-9_./-]
    public static VineId parse(String value);               // "ns:path"; toString() canonical
}
public enum DescriptorClass { STRUCTURAL, DESIGN }
/** Consumer-definable kind of registrable content — the extension point. */
public interface DescriptorType<D> {
    VineId registryId();            // e.g. vine:block, mymod:ability
    Codec<D> codec();               // DFU Codec: Java/JSON/network all derive from it
    DescriptorClass descriptorClass();
    boolean syncToClient();         // DESIGN only: synced dynamic registry vs server-only
    default boolean skipWhenEmpty() { return true; } // SKIP_WHEN_EMPTY on the wire
}
public final class VineRegistries {
    public static <D> void defineType(DescriptorType<D> type);           // engine-boot phase only
    public static <D> Holder<D> register(DescriptorType<D> type, VineId id, D data);
    public static <D> Optional<Holder<D>> get(DescriptorType<D> type, VineId id);
}
public interface Holder<D> { VineId id(); D value(); int runtimeId(); } // runtimeId = persistent-map slot
```

The `Codec` in these signatures is `com.mojang.serialization.Codec` — the
single external Mojang *library* type allowed in vine-api signatures (§5.1);
no `net.minecraft` or loader types ever appear.

**Internals** (vine-core `internal.registry`): `DescriptorStore` = per-type
sorted maps, frozen at sub-01 `REGISTRIES_FROZEN`; duplicates and post-freeze
writes throw. **Structural** descriptors → static startup registries (one
JVM-session freeze). **Design** descriptors → vanilla **dynamic datapack
registries**: datapack override, `/reload`, server→client sync come free; JSON
layout `data/<entry-ns>/<registry-ns>/<registry-path>/<entry>.json` (vanilla
convention, familiar pack tooling). Structural JSON is read once before
freeze; never hot-reloadable. **Persistent ID map**: engine-owned per-world
`VineId`↔int map in level data (`vine_id_map` SavedData) — Forge's
registry-persistence idea, engine-owned so Fabric is identical.
Missing-mapping policy per consumer mod: `KEEP` (default — placeholder, data
retained), `DROP`, `FAIL` (refuse load).

**Driver contract** (vine-spi `RegistryDriver`):

- Structural: **NF** `RegisterEvent`/`DeferredRegister`; **Fabric**
  `Registry.register` at mod init. SPI contract is only "materialized before
  vanilla freeze" — loader freeze-timing differences absorbed per cell.
- Design: **NF** `DataPackRegistryEvent.NewRegistry` with the descriptor
  `Codec` (+ network codec when syncing); **Fabric**
  `DynamicRegistries.registerSynced` (`register` if server-only), honoring
  `skipWhenEmpty`.
- Dynamic registries are **per-world**: rebind on world load, never cache
  holders across loads; `/reload` re-fires descriptor-changed on the sub-01
  bus; join sync asserted by TCK.
- **Content pipeline**: driver-owned deterministic per-cell datagen run cooks
  blockstate/item-model/`sounds.json` from consumer source assets; golden
  fixtures in vine-tck. Consumers never run per-version datagen.
- 26.x: same patterns, unobfuscated names; drift probed via
  `VineEngine.supports(...)`, never version strings.

## 3. Stages

### Stage A — `VineId` + descriptor core

- [x] **Do:** `VineId`, `DescriptorType`/`DescriptorClass`/`Holder`,
  `DescriptorStore` + freeze, `VineRegistries` Java path; codec round-trip of
  a trivial record descriptor. No driver work. Touches: vine-api `registry`,
  vine-core `internal.registry`.
- **Acceptance:** vine-core unit tests: ID validation/round-trip, duplicate
  rejection, freeze, codec round-trip.
- **Bootstrap prompt:**
  > Implement SUB-02 Stage A per sub-02 §2 exactly (plan §5.2; conventions
  > docs/README.md): the `dev.vineengine.vine.registry` package plus vine-core
  > `DescriptorStore` freezing on sub-01 `REGISTRIES_FROZEN`. Java 21 bytecode,
  > no `net.minecraft` types in vine-api. Acceptance: the listed unit tests.

### Stage B — structural registration on both 1.21.1 drivers (NF ∥ Fabric)

- [x] **Do:** `RegistryDriver` SPI; NF materialization via `RegisterEvent`,
  Fabric via `Registry.register` at mod init; testmod structural marker type
  (not blocks — sub-07) + one entry; boot log
  `vine: materialized N structural entries`. Touches: vine-spi,
  driver-1.21.1-neoforge, driver-1.21.1-fabric, vine-testmod.
- **Acceptance:** headless boot on both 1.21.1 cells; `VineRegistries.get`
  resolves the holder post-freeze on each; TCK scenario green on both.
- **Bootstrap prompt:**
  > Implement SUB-02 Stage B: `internal.spi.RegistryDriver.materializeStructural(DescriptorStore)`
  > invoked at NF's `RegisterEvent` and Fabric's `Registry.register` in mod
  > init; wire driver-1.21.1-neoforge and driver-1.21.1-fabric; add testmod
  > type + entry. Acceptance: boot log line + post-freeze `get` on headless
  > servers of both 1.21.1 cells. No loader types in vine-api; Mixins only in
  > drivers.

### Stage C — design descriptors on dynamic registries, both 1.21.1 loaders

- [x] **Do:** NF `DataPackRegistryEvent.NewRegistry`, Fabric
  `DynamicRegistries.registerSynced`; vanilla JSON layout; `syncToClient` /
  `skipWhenEmpty`; datapack override; `/reload` rebinding without cross-world
  caching. Testmod: design type + JSON entry + overriding datapack fixture.
  Touches: vine-core, vine-spi, both 1.21.1 drivers, vine-testmod, vine-tck.
  - **Landed 2026-09-24:** `DesignRegistryView` + `RegistryDriver.registerDesign`
    + `DriverContext.designRegistries()/reportDesignEntries(...)`.
    `DescriptorStore` keeps a per-type design-entry slot (writable after freeze
    by design — entries arrive from datapacks at world load), and
    `VineRegistries.get` resolves structural first, then datapack-loaded design
    entries (runtime id = load position). Each report replaces the previous set
    and fires one `RegistryRegister` hook event per entry, so nothing is cached
    across worlds. NF registers dynamic registries inside
    `DataPackRegistryEvent.NewRegistry` — reading the type view *fresh* at that
    moment, because the driver's bootstrap runs inside the mod constructor while
    consumer initializers run after boot (the stale-view bug the NF run caught:
    zero registries, zero entries). Fabric registers at mod init via
    `DynamicRegistries.registerSynced` (synced types) / `register` (server-only).
  - **Evidence:** `vine_test:design_registry` TCK scenario — JSON entry loads
    from a world datapack, is replaced by an override pack, and a rewritten pack
    is re-read across graceful reboots (value 1 → 2 → 3 asserted through
    `VineRegistries.get`). **12/12 scenarios PASS on both 1.21.1 cells.**
  - **Seams (documented):** the dev harness does not load the testmod's bundled
    `data/` as a datapack source, so JSON loading/override/re-read is proven
    through world datapacks; shipping consumer JSON inside a mod jar is a
    packaging-wave check. Client-side join-sync receipt needs the sub-21 client
    runner (headless server proves registration + per-world rebinding only).
- **Acceptance:** TCK on both 1.21.1 cells: JSON loads, override wins,
  `/reload` re-reads, join sync delivers values, loaders agree.
- **Bootstrap prompt:**
  > Implement SUB-02 Stage C per the driver contract in sub-02 §2: ride
  > vanilla dynamic datapack registries (NF event / Fabric
  > `DynamicRegistries.registerSynced`) with the descriptor `Codec`; never
  > cache dynamic holders across world loads; rebind on load and `/reload`.
  > Acceptance: TCK JSON-load/override/reload/join-sync on both 1.21.1 drivers.

### Stage D — persistent ID map + missing-content policy

- [ ] **Do:** per-world `VineId`↔int map in level data; stable assignment;
  `KEEP`/`DROP`/`FAIL` policy (default KEEP); placeholders retain unknown-ID
  data; remap logging. Touches: vine-core, vine-spi, drivers, vine-tck.
- **Acceptance:** TCK save → remove entry → load: KEEP keeps placeholder+data,
  DROP strips, FAIL refuses; re-adding restores the original ID. Both 1.21.1
  drivers.
- **Bootstrap prompt:**
  > Implement SUB-02 Stage D: engine-owned persistent ID map in level data
  > (engine-owned so Fabric is identical to NF); stable ints per structural
  > `VineId`; per-mod missing-mapping policy on world load + remap log.
  > Acceptance: TCK save/remove/load scenarios on both 1.21.1 drivers.

### Stage E — content pipeline (datagen) *(parallelizable with D)*

- [ ] **Do:** driver-owned per-cell Gradle datagen run cooking
  blockstate/item-model/`sounds.json` from source assets; deterministic;
  golden fixtures in vine-tck. Touches: drivers, vine-testmod assets, vine-tck.
- **Acceptance:** both 1.21.1 cells' output byte-identical to golden fixtures.
- **Bootstrap prompt:**
  > Implement SUB-02 Stage E (plan §5.2 content pipeline): per-1.21.1-cell
  > deterministic datagen from vine-testmod source assets, golden-diffed
  > byte-for-byte in vine-tck CI. Acceptance: both cells match fixtures.

### Stage F — JSON authoring for structural + extension-point hardening

- [ ] **Do:** startup JSON load for structural descriptors (jar + datapack
  locations, read before freeze); consumer-defined `DescriptorType` proven
  end-to-end (Java + JSON + design sync). Touches: vine-api, vine-core,
  drivers, vine-testmod.
- **Acceptance:** TCK: external-style design type loads from a datapack and
  syncs; structural JSON entry ≡ its Java-registered twin. Both 1.21.1 drivers.
- **Bootstrap prompt:**
  > Implement SUB-02 Stage F: JSON authoring for structural descriptors (read
  > before registry freeze; not hot-reloadable) and prove the extension point
  > with a `DescriptorType` defined outside vine-core over Java + JSON + sync.
  > Acceptance: TCK scenario on both 1.21.1 loaders.

## 4. Problems & blockers

- **Static registry timing differs per loader** (NF phased `RegisterEvent` vs
  Fabric mod-init). Mitigation: SPI "before vanilla freeze" contract + TCK boot
  assertion.
- **Dynamic registries are per-world** — holders die with the server; resolve
  lazily per world, cross-world caching is a defect (Stage C TCK catches it).
- **ID remapping when content vanishes** — unpersisted ints shift, worlds
  corrupt silently. Stage D is the fix; KEEP preserves data until content returns.
- **`/reload` vs live gameplay state** — design values swap under running
  logic. Mitigation: descriptor-changed event; javadoc invariant "design
  holders are volatile".
- **26.x dynamic-registry/sync API drift.** Mitigation: `supports()` probes,
  per-cell absorption; spike with sub-19.

## 5. Verification

Owned TCK scenarios (1.21.1-NF + 1.21.1-Fabric; 26.x when sub-19 lands):

1. Java-path registration → boot → query (M0 gate).
2. JSON design load + datapack override + `/reload` + join sync.
3. ID map round-trip; KEEP/DROP/FAIL on vanished content.
4. Structural JSON authoring ≡ Java authoring (same holder value).
5. Datagen golden fixtures byte-identical per cell.

No surface ships without its scenario on one driver per loader family (§8).

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.*` (public) /
  `dev.vineengine.vine.internal.*` (SPI, drivers); shared modules Java 21
  bytecode; 26.x drivers Java 25. Descriptors are data; the `Codec` is the
  single source of truth for every representation.
- **Comment policy:** javadoc states *why* and invariants (e.g. "design
  holders are volatile across `/reload`"); driver comments name the absorbed
  loader/version difference; no narration.
- **Forbidden:** version-string parsing (probes only, §5.12); loader classes
  in API signatures; Mixins outside drivers; global vanilla changes (Minimal
  Footprint — consumer-less VINE registers nothing); caching dynamic-registry
  holders across world loads.
- **Done means:** every stage checkbox ticked, acceptance green on all covered
  cells, status `done`, dashboard row updated in `docs/README.md`.
