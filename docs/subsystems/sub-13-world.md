# SUB-13 — World & population

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M3 · **Depends on:** SUB-02, SUB-03, SUB-05 (soft: SUB-08 — multipart entities count once in population control) · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.18, §5.2, §8 · **Module(s):** vine-api | vine-client-api | vine-core | vine-spi | drivers

## 1. Purpose

Controls *where* content lives and *how much* exists: fixed locale/activity
dimensions, feature/structure descriptors on vanilla worldgen, normalized
biome modification, data-driven spawn replacement, population guardrails
(density caps, territories, despawn rules — the guardrail for many large
multipart `VineBrain` entities + herds), clock/weather normalization, and
teleport/waypoint primitives with explicit respawn binding. **Non-goals:**
per-party instanced maps (explicitly out of scope, §5.18 — seamless overworld
is primary), custom noise/density worldgen, dimension UIs.

## 2. Design

**API** (`vine-api`, `dev.vineengine.vine.world`) — design descriptors
(sub-02 machinery, `Codec` records, Java + JSON paths):

```java
public record DimensionDescriptor(VineId id, VineId stemType, long fixedTime, Optional<VoxelData> effects) {}
public record FeatureDescriptor(VineId id, VoxelData configured, VoxelData placement) {}
public record StructureDescriptor(VineId id, VineId templatePool, VoxelData placement) {}
public record BiomeModification(VineId id, BiomeSelector selector, List<BiomeOp> ops) {}
public record SpawnRule(VineId id, SpawnSelector selector, SpawnAction action, int priority) {}

public interface Population {
    void setDensityCap(VineId consumer, RegionSpec region, VineId entityType, int cap);
    void assignTerritory(VineId entity, RegionSpec region);
    void setDespawnRule(VineId consumer, DespawnRule rule);
}
public interface Teleport {
    PendingTeleport request(TeleportRequest req);   // cross-position & cross-dimension
    void registerCostHook(TeleportCostHook hook);   // cancel/modify; consumer owns economy
    void registerCooldown(VineId key, Duration cooldown);
}
public interface Waypoints {
    Waypoint register(WaypointDescriptor desc);     // VoxelData-persisted
    void activate(PlayerRef p, VineId waypoint);    // unlocks use; ≠ binding
    void bindRespawn(PlayerRef p, VineId waypoint); // DELIBERATE act (Waystones lesson, §5.18)
}
```

Custom weather states are consumer world data + client presentation: core runs
a `VoxelData`-backed weather state machine; `vine-client-api` exposes
`WeatherPresentation` hooks (fog/sky/precipitation) for sub-17. Clock:
monotonic world clock + day-phase events; consumers never read raw `dayTime`.

**Internals** (`vine-core`): priority-ordered spawn-rule engine at driver
spawn hooks (suppress → redirect → replace); `PopulationLedger` (per-region
counts + territory map) and waypoints/bindings persisted as `VoxelData`.
Consumer-scoped: no rules → no hooks (Minimal Footprint).

**Driver contract** (`vine-spi` `WorldDriver`): `installSpawnHooks`,
`applyBiomeModifications`, `emitWorldgenDescriptors`, `clockEvents`,
`safeTeleport` (chunk tickets), `weatherStateSync`. Per-cell: **NF** =
biome-modifier codecs + staged spawn events; **Fabric** = Biome Modification
API + spawn callbacks — normalized to one `BiomeOp` model and a two-phase
(check/finalize) spawn model. Worldgen JSON churn per drop absorbed by driver
emission, probe-guarded (§5.12); 1.21.1 hooks Mixin-quarantined (§5.9), 26.x
unobfuscated = direct calls. Descriptors ride dynamic datapack registries
(§5.2): `/reload` re-evaluates live; weather synced via sub-05.

## 3. Stages

### Stage A — Worldgen & biome modification

- [ ] **Do:** dimension/feature/structure descriptors + `Codec`s;
  dynamic-registry registration via sub-02; drivers emit cell-correct worldgen
  JSON; `BiomeModification`/`BiomeOp` over NF biome modifiers + Fabric Biome
  Modification API.
- **Acceptance:** testmod JSON adds one locale dimension, one placed feature,
  one biome spawn entry; identical on both loaders; `/reload` applies edits.
- **Touches:** vine-api, vine-core `internal.world.{gen,biome}`, vine-spi, drivers.
- **Bootstrap prompt:**
  > Implement SUB-13 Stage A per docs/subsystems/sub-13-world.md §2: the four
  > worldgen/biome descriptors in `dev.vineengine.vine.world`, dynamic
  > datapack registries via sub-02, cell-correct emission in both 1.21.1
  > drivers. Acceptance as above. Binding: Prime Invariant; probe-guard
  > layout differences, never version strings (§5.12).

### Stage B — Spawn replacement

- [ ] **Do:** `SpawnRule` registry + priority engine; driver spawn hooks;
  consumer-scoped registration.
- **Acceptance:** TCK spawn-control scenario: zombies suppressed in a tagged
  region, redirected to a consumer entity elsewhere; zero effect with rules
  removed (Minimal Footprint).
- **Touches:** vine-core `internal.world.spawn`, vine-spi, drivers.
- **Bootstrap prompt:**
  > Implement SUB-13 Stage B: priority-ordered data-driven spawn rules
  > (suppress/redirect/replace, §2) via driver hooks normalized to
  > check/finalize phases, dormant without consumers. Acceptance: TCK
  > spawn-control scenario green on both 1.21.1 loaders.

### Stage C — Population control

- [ ] **Do:** `PopulationLedger`, density caps, territory assignment, despawn
  rules; `VoxelData` persistence; multipart entities (sub-08) count once.
- **Acceptance:** cap of 20 herd members enforced live; ledger + territories
  identical after save/load.
- **Touches:** vine-core `internal.world.population`, sub-03 store.
- **Bootstrap prompt:**
  > Implement SUB-13 Stage C: `VoxelData`-persisted density caps, territory
  > assignment, despawn rules (`PopulationLedger`; `VineId`-keyed territory
  > records re-resolved on load). Acceptance: cap enforced live; state
  > round-trips save/load; a multipart entity counts once.

### Stage D — Clock, weather, teleport & waypoints

- [ ] **Do:** clock + day-phase events; weather state machine +
  `WeatherPresentation` hooks; `Teleport` with cost/cooldown hooks +
  driver-side chunk tickets; waypoint registry; explicit respawn binding.
- **Acceptance:** 500-block cross-dimension teleport lands on a loaded chunk;
  `activate` leaves vanilla bed respawn untouched; `bindRespawn` moves it;
  cooldown blocks a repeat teleport.
- **Touches:** vine-api, vine-client-api, vine-core `internal.world.{clock,
  teleport}`, drivers.
- **Bootstrap prompt:**
  > Implement SUB-13 Stage D per §2: clock/weather normalization, safe
  > teleport (chunk ticket hold-until-commit, release after), VoxelData
  > waypoints, explicit respawn binding (activation ≠ binding, §5.18).
  > Acceptance: all stage bullets pass on both 1.21.1 loaders.

### Stage E — TCK performance scenario definition (owner)

- [ ] **Do:** author the perf scenario spec: N large multipart entities +
  herds in an overworld; per-cell tick-budget numbers + measurement hooks for
  sub-21 to execute.
- **Acceptance:** scenario merged into vine-tck; baseline budget run recorded
  on the two 1.21.1 cells pre-M4; deferred 26.x re-run on M4 landing
  (acceptance-matrix rule).
- **Touches:** vine-tck scenario definition.
- **Bootstrap prompt:**
  > Author SUB-13 Stage E: the TCK performance scenario definition (N large
  > multipart entities + herds, per-cell tick budget, §5.18/§8) plus
  > measurement hooks; sub-21 executes it. Acceptance: scenario runs headless
  > on both 1.21.1 cells pre-M4 (26.x re-run deferred to M4), reporting tick time against budget.

## 4. Problems & blockers

- **Worldgen JSON churn across drops**: keys/layout shift 1.21.1 → 26.x;
  drivers own emission, probe-guarded. Spike at sub-19 bring-up; owner:
  driver files.
- **Spawn-pipeline event differences per loader**: NF staged events vs Fabric
  callbacks fire at different points; SPI normalizes to check/finalize —
  missing point → quarantined driver Mixin. Owner: this file.
- **Chunk tickets for long teleports**: destination must be ticket-loaded
  before transfer or the entity ticks unloaded; `safeTeleport` contract =
  hold-until-commit, release after (TCK long-teleport scenario).
- **Territory persistence across reloads**: region→entity mapping must survive
  entity UUID churn; `VineId`-keyed territory records, re-resolved on load
  (Stage C acceptance covers round-trip).

## 5. Verification

- **Owns the TCK performance scenario definition** (§5.18/§8) — Stage E.
- TCK spawn-control scenario on ≥2 drivers, one per loader family.
- Worldgen descriptor round-trip (JSON → placement → `/reload` override) both
  loader families; waypoint/binding `VoxelData` golden fixture.
- Perf: ledger tick cost inside scenario budget; zero measurable cost with no
  consumers.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.world.*` (public) /
  `dev.vineengine.vine.internal.world.*`; Java 21 shared, Java 25 in 26.x
  drivers; descriptors are data (`Codec`, Java + JSON paths).
- **Comment policy:** javadoc states *why* + invariants (`bindRespawn`:
  deliberate act; activation never implies binding); driver comments name the
  per-loader/drop difference absorbed.
- **Forbidden:** version-string parsing; loader/`net.minecraft.*` types in API
  signatures; Mixins outside drivers; hooks active with zero consumers;
  per-party instancing (out of scope, §5.18).
- **Done means:** all boxes ticked, acceptance green on all four cells, status
  `done`, dashboard row updated in `docs/README.md`.
