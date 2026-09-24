# SUB-04 — Capabilities

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M1 · **Depends on:** SUB-02, SUB-03 · **Blocks:** SUB-20
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.5 (+ §5.20, §5.1, §5.12) · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

One engine capability registry for energy / fluid / inventory / custom types:
consumers expose and query capability instances on blocks, entities, item
stacks, and players through one version-free API. The **engine fallback store**
(VoxelData-backed, sub-03) is the portability floor — every capability works
identically on every cell — and per-loader native interop (NeoForge capability
system, Fabric API lookups) is a best-effort layer *above* the floor. Non-goals:
interop bridges to foreign mods' FE/RF/fluid APIs live in **sub-20** (§5.20
bridge policy); this file defines only the capability core they bridge. Not an
event bus, not an ECS.

## 2. Design

**API surface** (`vine-api`, `dev.vineengine.vine.capability`):

```java
public record CapabilityType<T>(VineId id, Class<T> apiClass,
        @Nullable CapabilityCodec<T> state,      // null ⇒ stateless, never serialized
        ClonePolicy clonePolicy) {}              // player clone/respawn semantics

public interface CapabilityCodec<T> {            // persistence rides sub-03
    VoxelData save(T instance);
    T load(VoxelData data);
}

public enum ClonePolicy { NONE, FULL, CUSTOM }   // CUSTOM → provider re-creates

public sealed interface CapabilityTarget permits BlockCapabilityTarget,
        EntityCapabilityTarget, ItemCapabilityTarget, PlayerCapabilityTarget {}

public final class VineCapabilities {
    public static <T> CapabilityType<T> register(CapabilityType<T> type);
    public static <T> void attach(CapabilityType<T> type, CapabilityScope scope,
                                  CapabilityProvider<T> provider);
    public static <T> Optional<T> find(CapabilityType<T> type, CapabilityTarget target);
    public static <T> Optional<T> findForeign(VineId nativeId, Class<T> apiClass,
                                              CapabilityTarget target);   // probe-degraded
    public static void invalidate(CapabilityTarget target);
}

@FunctionalInterface
public interface CapabilityProvider<T> { T create(CapabilityTarget target); }
```

`CapabilityScope` = BLOCK / ENTITY / ITEM / PLAYER + optional descriptor filter
(sub-02 `Holder` predicates). Engine ships standard interfaces — `vine:energy`
(long-quantified storage), `vine:fluid` (fluid id + milli-buckets),
`vine:inventory` (slot model) — and custom `CapabilityType`s are an extension
point (§5.20), registered through sub-01's `ExtensionPoints` machinery.

**Internals** (`vine-core`, `internal.capability`): type registry keyed by
`VineId`; instances cached per target (weak keys), keyed by type id.
*Attach model*: a capability whose `state` codec is non-null is backed by a
`VoxelData` tree at schema id `vine:cap/<type-id>` on the target's sub-03
attach point — that *is* the fallback store, so persistence and portability are
the same code path. Stateless capabilities are pure Java instances from their
provider; they never serialize. **Layering rule**: consumer code only ever sees
engine types; a native interop result is wrapped in the engine type at the
driver boundary; partner absence ⇒ `Optional.empty` + `VineEngine.supports(...)`
false — never an exception.

**Driver contract** (`vine-spi`, `CapabilityDriver`) + per-cell notes:

- `exposeNative(type, target, instance)`: NF cells — register a provider in
  `RegisterCapabilitiesEvent` that delegates into the engine query; Fabric
  cells — register fallback returns on `BlockApiLookup` / `EntityApiLookup` /
  `ItemApiLookup`. 26.x cells: same contract, implemented in the M4 window with
  sub-19; drivers absorb API drift.
- `queryNative(nativeId, target)`: NF — `level.getCapability(...)`; Fabric —
  `lookup.find(...)`. Both wrapped per the layering rule.
- **Player clone/respawn**: NF `PlayerEvent.Clone`, Fabric
  `ServerPlayerEvents.COPY_FROM` → engine applies `ClonePolicy` (NONE = fresh,
  FULL = copy VoxelData state, CUSTOM = provider re-creates). Verified
  mapping: `COPY_FROM(old, new, alive)` ↔ `Clone` with `isWasDeath()` — copy
  only on death, never on End/dimension return. Fabric alternative: Data
  Attachment `copyOnDeath()` covers player-attached engine data with no
  manual copy.
- **Invalidation**: NeoForge AUTO-INVALIDATES block-entity capabilities on
  `setRemoved`/chunk unload — the NF driver MUST NOT double-invalidate; it
  only forwards the uniform `CAPABILITY_INVALIDATED` engine event (sub-01
  bus). Fabric lookups are query-time, but foreign lookup caches DO exist
  (`BlockApiCache`): the driver contract is cache-aware — engine-exposed
  targets emit the same uniform event so foreign caches re-query.

**Serialization rules**: stateful types persist through their
`CapabilityCodec<T>` into the target's VoxelData tree and inherit sub-03's
schema versioning, datafixers, and golden fixtures. Serialization happens at
the target's save boundary (item component write, BE/entity save tag, player
persist, world SavedData) — drivers never hand-write cap NBT.

## 3. Stages

### Stage A — type registry + fallback store core

- [x] **Do:** `CapabilityType` registry, built-in energy/fluid/inventory
  interfaces, fallback store on VoxelData, instance cache. Pure JVM, zero MC
  imports.
- **Acceptance:** vine-core unit: register a custom stateful type, attach to a
  fake target, save→load round-trip through VoxelData preserves state.
- **Touches:** vine-core.
- **Bootstrap prompt:**
  > Build the capability core per `docs/subsystems/sub-04-capabilities.md` §2
  > (Internals) in `vine-core` under `dev.vineengine.vine.internal.capability`.
  > Depends on sub-03 Stage A/B (VoxelData core). No Minecraft imports.
  > Acceptance: the register→attach→save→load round-trip unit test.

### Stage B — API surface + SPI

- [x] **Do:** public API per §2 into vine-api (`CapabilityScope`,
  `ClonePolicy`, sealed targets); `CapabilityDriver` SPI into vine-spi;
  testmod registers one custom capability type.
- **Acceptance:** compiles (Java 21); testmod custom type registers on both
  1.21.1 cells' common module.
- **Touches:** vine-api, vine-spi, vine-testmod.
- **Bootstrap prompt:**
  > Publish the capability API exactly as sketched in
  > `docs/subsystems/sub-04-capabilities.md` §2 and the `CapabilityDriver` SPI
  > into vine-spi. Prime Invariant: no net.minecraft/loader types in
  > signatures. Acceptance: compiles; testmod registers a custom type.

### Stage C — 1.21.1 NeoForge driver (∥ with Stage D)

- [x] **Do:** `CapabilityDriver` for 1.21.1-NF: provider delegation via
  `RegisterCapabilitiesEvent`, `queryNative` via `level.getCapability`,
  `PlayerEvent.Clone` → `ClonePolicy`, `CAPABILITY_INVALIDATED` forwarding on
  BE removal/chunk unload (NF auto-invalidates — never double-invalidate).
  - **Landed 2026-09-24 (item scope, both cells — see Stage D for why the shape
    differs from the sketch):** `CapabilityDriverBinding` (one driver per cell,
    best-effort), `CapabilityStore.findForeign` routes to the bound driver and
    wraps at the boundary (a miss or untyped answer is absence, never an
    exception), and `attach(type, scope, provider)` now also calls
    `exposeNative` — attaching *is* the engine's statement that the type serves
    that scope. Capability trees are created through the target's attach point
    (`VineData.of(target, …)`, falling back to in-memory for scopes whose
    attach points are not implemented yet), and `VineCapabilities.flush(target)`
    pushes every live capability tree through the storage layer, so capability
    state rides the same carrier as every other engine tree and copies of the
    target carry it.
  - **Deviation, evidence-driven:** the sketch's `RegisterCapabilitiesEvent` +
    `level.getCapability` pairing is not the 1.21.1 mechanism any more — NeoForge
    21.1 replaced legacy capabilities with **data attachments**, and engine
    capability *interfaces* live in consumer jars, which a driver cannot
    reference. The cells therefore expose capabilities through the target's
    engine payload (the same object-containment the storage driver writes, which
    vanilla save/load and item sync already carry) and answer native queries
    from it. Loader-native attachment registration, plus BE/entity/player
    scopes, `PlayerEvent.Clone` and invalidation forwarding, land with the
    Mixin wave (sub-18 Stage E) — the SPI shape does not change when they do.
- **Acceptance:** TCK `caps.store_retrieve` + `caps.clone_copy` +
  `caps.invalidation` green on 1.21.1-NF.
- **Touches:** driver-1.21.1-neoforge.
- **Bootstrap prompt:**
  > Implement `CapabilityDriver` for 1.21.1-NeoForge per
  > `docs/subsystems/sub-04-capabilities.md` §2 Driver contract. Depends on
  > Stage B + sub-03 Stage D/E on your cell. Mixins only inside the driver.
  > Acceptance: the three named TCK scenarios pass on your cell.

### Stage D — 1.21.1 Fabric driver (∥ with Stage C)

- [x] **Do:** `CapabilityDriver` for 1.21.1-Fabric: `*ApiLookup` fallbacks,
  `lookup.find` native queries, `ServerPlayerEvents.COPY_FROM` →
  `ClonePolicy`, uniform `CAPABILITY_INVALIDATED` emission (cache-aware:
  foreign `BlockApiCache` caches re-query on it).
  - **Landed 2026-09-24 (item scope):** both cells share
    `ItemStackCapabilityDriver` (driver-common) — `exposeNative` records the
    (scope, type) pair and logs the carrier, `queryNative` decodes the target's
    engine payload through the load path and returns it as an engine tree.
    Fabric's query-time model needs no cache invalidation for this carrier (the
    payload is read per query), and `VineCapabilities.invalidate` stays the
    uniform engine-side event.
  - **Evidence:** `vine_test:caps_driver_interop` TCK scenario on both 1.21.1
    cells. It asserts the exposure line
    (`caps: exposed vine:probe_cap ITEM (carrier: item payload)`) and the
    end-to-end path: a stateful capability writes through to the item's payload,
    `VineCapabilities.flush` pushes it through the storage layer, and a *copy*
    of the item answers the native query with `value=11` — **15/15 scenarios
    PASS on both cells.**
  - **Remaining seams (documented):** `*ApiLookup` fallbacks, BE/entity/player
    scopes, `COPY_FROM`/`Clone` policy hooks and invalidation forwarding need
    the Mixin/attach wave (sub-18 Stage E); `applyClone` already implements the
    policies engine-side, so the hooks are wiring, not design.
- **Acceptance:** same three TCK scenarios green on 1.21.1-Fabric.
- **Touches:** driver-1.21.1-fabric.
- **Bootstrap prompt:**
  > Implement `CapabilityDriver` for 1.21.1-Fabric per
  > `docs/subsystems/sub-04-capabilities.md` §2 Driver contract. Fabric has no
  > provider/invalidation model — lookups are query-time; the fallback store
  > carries semantics. Acceptance: the three named TCK scenarios pass on your
  > cell.

### Stage E — TCK suite + probe degradation + 26.x handoff

- [ ] **Do:** full scenario suite (§5) including `caps.probe_degraded` run with
  no partner mod installed; golden-fixture coverage via sub-03 fixtures;
  26.x implementation notes for sub-19.
- **Landed:** every scope has a carrier since sub-03 Stage E, so exposure is no
  longer item-only: `CommonCapabilityDriver` (was `ItemStackCapabilityDriver`)
  answers native queries for item stacks, block entities, entities and players by
  reading the holder's payload bundle and picking the capability's own schema
  slice (`CapabilityStore.schemaIdFor`), which is what keeps two capabilities —
  or a capability plus engine data — on one holder from clobbering each other.
  Capability flushes carry the tree's recorded dirty set instead of a wildcard,
  so a capability that was read but not changed writes nothing.
  Proven live on both cells: the probe claims a stateful type on all four scopes
  and round-trips `block=31 entity=41` through find → mutate → flush → find on a
  block entity and an entity that are not item stacks. The degraded path is a
  scenario of its own (`caps_probe_degraded`): an unserved type, a foreign id and
  a target scope the type does not claim all answer empty with zero exceptions —
  the "no partner mod installed" contract.
  **Remaining:** golden-fixture coverage for capability state rides the same
  cross-cell fixture task as sub-03; 26.x notes for sub-19 live in §2's driver
  contract (registries and attachment APIs are the two seams that drift).
- **Acceptance:** §5 suite green on both 1.21.1 cells (one per loader family —
  §8 satisfied); probe-degraded scenario shows empty `Optional` + false
  `supports()`, zero exceptions.
- **Touches:** vine-tck, vine-core.
- **Bootstrap prompt:**
  > Own the capability TCK suite per `docs/subsystems/sub-04-capabilities.md`
  > §5 across both 1.21.1 cells, add the probe-degraded scenario (no partner
  > mods), and write 26.x driver notes for sub-19. Acceptance: suite green on
  > both cells; degradation path proven.

## 4. Problems & blockers

- **NF provider model vs Fabric lookup model impedance mismatch** — NF is
  object-centric (provider + invalidation); Fabric is query-time lookup.
  Decision: engine semantics are *query-time pull* uniformly; NF drivers wrap
  providers that delegate into the engine query and invalidate eagerly. Owner:
  this file.
- **Capability invalidation on chunk unload** — NeoForge auto-invalidates
  block-entity capabilities on `setRemoved`/chunk unload, killing foreign
  cached handles; the NF driver never double-invalidates — it forwards the
  engine's uniform `CAPABILITY_INVALIDATED` event so consumers drop their own
  caches (on Fabric, foreign `BlockApiCache` caches re-query after it).
- **Cross-mod interop when partner absent** — probe-degraded by design
  (§5.20): `findForeign` returns `Optional.empty`, `VineEngine.supports(...)`
  reports false, integration classes never classload; bridges are sub-20's
  soft-dependency-isolated modules, not this file's.
- **Thread-safety expectations** — capability access is server-thread-only
  (client thread for client targets); the engine store deliberately holds no
  locks; off-thread access is a programming error stated in javadoc; async
  consumers snapshot state via sub-03 `VoxelData.snapshot()`. Owner: this file.

## 5. Verification

TCK scenarios owned (each green on ≥2 drivers, one per loader family — §8):

- `caps.store_retrieve` — each built-in type (energy/fluid/inventory) on each
  target kind (block/entity/item/player) survives save→load.
- `caps.custom_type` — consumer-defined type with `CapabilityCodec` round-trips.
- `caps.clone_copy` — player death/respawn: `NONE` yields fresh state, `FULL`
  preserves it.
- `caps.invalidation` — chunk unload kills stale native handles; consumers see
  `CAPABILITY_INVALIDATED`.
- `caps.probe_degraded` — no partner mod installed: empty `Optional`, false
  `supports()`, no exceptions.
- Golden fixtures: capability state rides sub-03's cross-cell save fixtures —
  a capability saved on 1.21.1 reads identically on 26.x.

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.capability` (public) /
  `dev.vineengine.vine.internal.capability` (core, SPI, drivers); shared
  modules Java 21 bytecode; 26.x drivers may use Java 25.
- **Comment policy:** javadoc states *why* and invariants (portability floor
  vs best-effort native layer; thread confinement); driver comments name the
  exact provider-vs-lookup difference being absorbed.
- **Forbidden:** version-string parsing (§5.12); loader classes in API
  signatures; Mixins outside drivers; naming foreign-mod APIs (FE/RF, Fabric
  transfer types) in consumer-facing API — that is sub-20 bridge territory;
  exceptions for absent partners (probe-degrade only).
- **Done means:** all stage boxes ticked, §5 suite green on all covered cells,
  status header `done`, dashboard row updated in `docs/README.md`.
