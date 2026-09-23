# SUB-08 — Entities & VineBrain

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M2 · **Depends on:** SUB-02, SUB-03, SUB-05 · **Blocks:** SUB-09, SUB-10
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.13 · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

Engine-owned entity semantics hosted on the native game: entity descriptors,
the `VineBrain` behavior runtime (this file is its source of truth), multipart
entities, riding/mounts, territory semantics, engine attribute wrapper. VINE
owns the behavior scheduler; drivers supply primitives only — never map to
native goal-selector/`Brain` systems (would leak version semantics, §5.13).
Non-goals: animation/rendering (sub-09), spawn/population placement (sub-13 —
referenced only), damage pipeline ordering (sub-10 — consumes this part data).

## 2. Design

- **API surface** (`vine-api`):
  ```java
  public record EntityDescriptor(VineId id, AttributeSet attributes,
      Dimensions dimensions, List<PartDescriptor> parts,
      RideDescriptor ride, VineId spawnRule /* sub-13 ref */) {
      public static Codec<EntityDescriptor> codec(); }
  public record PartDescriptor(String name, VineId parentBone, Vec3d size,
      Vec3d offset, Map<DamageType, Float> damageMultipliers,
      float flinchThreshold, float breakThreshold) {}
  public interface VineBrain {
      void set(Node root);                 // sequence/selector/parallel/condition/action
      Blackboard blackboard();             // typed keys, persisted via VoxelData
      StateMachineBuilder stateMachine();  // FSM sugar lowering to nodes
  }
  ```
  Entity TYPES are STRUCTURAL descriptors (§5.2): `EntityDescriptor` and its
  `PartDescriptor`s register statically at startup via sub-02's structural
  path (NF `RegisterEvent`/`DeferredRegister` vs Fabric `Registry.register`,
  absorbed per driver) — never dynamic registries, never `/reload`. Only the
  DESIGN data (behavior presets, stats, attribute sets as data) may ride
  sub-02 dynamic registries and JSON hot-reload.
- **Internals** (`vine-core`, headless): nodes tick at 20 TPS with
  `SUCCESS/FAILURE/RUNNING`; `Parallel` takes a declared success policy.
  Determinism contract: same blackboard + same primitive results ⇒ same node
  trace; the golden tick-trace TCK replays a recorded primitive-result tape on
  every cell. Per-part state (wounds, broken) is `VoxelData` keyed by part name.
- **Driver contract** (`vine-spi` + per-cell): `EntityDriverSpi` primitives
  ONLY — `spawn/despawn`, `requestPath(pos) -> PathHandle` (async,
  cancellable), `moveTo/lookAt`, `hasLineOfSight`, `queryTargets`, attribute
  binding, part hosting, rider-input forwarding. Registration timing: NF via
  deferred registers + attribute-creation event; Fabric via its default-
  attribute registry; 26.x per then-current lifecycle — absorbed in drivers.
  Multipart hosting: NeoForge formalizes `PartEntity`; Fabric hosts the
  vanilla mechanism manually.
- **Data flow / sync:** part positions are server-authoritative, computed by
  the sub-09 evaluator; published fixed-point-quantized, delta-compressed over
  sub-05 (deadband 0.02 blocks). Rider input: client→server over sub-05,
  validated server-side.

### Worked example — multipart hitbox (first-class)

A wyvern declares parts `head/neck/body/tail/wing_l/wing_r`, each parented to
a sub-09 animation bone. `head` carries `{sever:1.25, blunt:1.0, shot:0.8}`
and a `flinchThreshold` letting sub-10 interrupt brain nodes; `tail` carries
a `breakThreshold` — cumulative damage past it flips the part's `VoxelData`
wound state to `broken`, changes its multipliers, and signals sub-09 to swap
the tail animation set. Each tick the sub-09 evaluator returns bone-attached
OBBs; the engine moves the `PartEntity` hosts server-side so sub-10's part
resolution sees truthful geometry on every cell. This wyvern is the TCK
multipart fixture.

## 3. Stages

### Stage A — descriptors, attributes, spawn-rule refs

- [ ] **Do:** `EntityDescriptor`/`PartDescriptor` + `Codec`s; attribute
  wrapper (base, min/max, modifiers); STRUCTURAL registration via sub-02's
  structural path (static, startup — no `/reload` for entity types); JSON
  hot-reload limited to design data (behavior presets, stats).
- **Acceptance:** JSON descriptor loads and spawns with descriptor attributes
  on 1.21.1-NF + 1.21.1-Fabric testmod.
- **Touches:** `dev.vineengine.vine.entity`, `dev.vineengine.vine.internal.entity`.
- **Bootstrap prompt:**
  > Implement SUB-08 Stage A per `docs/subsystems/sub-08-entities.md` (read
  > plan §5.13 + `docs/README.md` conventions). Descriptors + `Codec`s on
  > sub-02's STRUCTURAL registry path (static startup registration; entity
  > types never hot-reload); attribute wiring per loader inside 1.21.1 drivers
  > only (Prime Invariant). Acceptance: JSON entity spawns on both 1.21.1
  > cells with attributes applied. Java 21 bytecode in shared modules.

### Stage B — VineBrain core + golden tick-trace TCK

- [ ] **Do:** node model, typed blackboard on `VoxelData`, FSM sugar,
  deterministic tick with injectable primitive-result tape.
- **Acceptance:** golden tick-trace (wyvern wander→alert→chase) replays
  byte-identically on 1.21.1-NF + 1.21.1-Fabric.
- **Touches:** `dev.vineengine.vine.brain`, `vine-core`, TCK.
- **Bootstrap prompt:**
  > Implement SUB-08 Stage B per the file. `VineBrain` fully headless in
  > `vine-core`; drivers NOT implemented — mock the SPI tape. TCK asserts
  > node-status sequences + blackboard diffs per tick. Acceptance: trace
  > matches across runs on both 1.21.1 cells.

### Stage C — driver primitives (1.21.1 cells)

- [ ] **Do:** implement `EntityDriverSpi` for 1.21.1-NF + 1.21.1-Fabric; 26.x
  gated behind `VineEngine.supports(...)` until sub-19.
- **Acceptance:** Stage-B brain drives a live wyvern to a target across
  obstacles on both cells.
- **Touches:** `internal.entity.spi`, both 1.21.1 drivers.
- **Bootstrap prompt:**
  > Implement SUB-08 Stage C per the file. Map each SPI primitive to native
  > navigation/sensing in both 1.21.1 drivers; comment each absorbed
  > version/loader difference. Scheduler stays engine-side. Acceptance:
  > live-pathing wyvern scenario green.

### Stage D — multipart entities

- [ ] **Do:** part runtime (hosts, per-part `VoxelData`, flinch/break),
  `PartEntity` hosting per cell, position consumer interface toward sub-09,
  quantized delta sync.
- **Acceptance:** wyvern TCK: per-part multipliers resolve, tail breaks at
  threshold, positions sync within 1 tick on both 1.21.1 cells.
- **Touches:** `vine-core` part runtime, drivers, sub-05 channels.
- **Bootstrap prompt:**
  > Implement SUB-08 Stage D per the file. Until sub-09 lands, drive positions
  > from a scripted pose tape behind the same interface. NF: formal
  > `PartEntity`; Fabric: manual vanilla hosting — document the difference.
  > Acceptance: multipart TCK green on both 1.21.1 cells.

### Stage E — riding/mount API

- [ ] **Do:** `RideDescriptor` (seats, control scheme), ride API, input
  packets (sub-05) with server validation (rate, reachability).
- **Acceptance:** TCK riding roundtrip (mount, steer, dismount restores
  state) on both 1.21.1 cells.
- **Touches:** `vine.entity.ride`, drivers, sub-05.
- **Bootstrap prompt:**
  > Implement SUB-08 Stage E per the file. Engine owns ride semantics; drivers
  > forward passenger plumbing and input only. Acceptance: riding TCK
  > roundtrip green on both 1.21.1 cells.

### Stage F — territory semantics

- [ ] **Do:** leash ranges, area-transition intents (consumed by sub-13
  locales), herd/pack grouping (alpha + members) as blackboard/node features;
  perf counters.
- **Acceptance:** TCK: herd follows alpha across leash boundary; intent fires
  once per crossing; counters exported for sub-13's perf scenario.
- **Touches:** `vine.brain.territory`, `vine-core`.
- **Bootstrap prompt:**
  > Implement SUB-08 Stage F per the file. Territory is engine semantics, not
  > worldgen — emit transition intents; sub-13 owns placement. Acceptance:
  > herd/leash TCK green on both 1.21.1 cells.

## 4. Problems & blockers

- **Registration timing per loader** — lifecycle events fire in different
  phases. Mitigation: drivers document their point; engine exposes one
  `EntityPhase.READY` signal. Owner: sub-08 + driver files.
- **Part-position sync bandwidth** — quantization + delta + 0.02-block
  deadband; budget ≤ 2.5 KB/s per multipart entity at 12 parts (measured
  Stage D; breach ⇒ raise deadband or batch per tick).
- **Pathfinding quality variance across versions** — contract asserts
  reachability/progress, never path shape; golden traces use tape mode.
- **Perf budget for N multipart entities** — target: 20 multipart × 12 parts
  + 100 herd members in one locale, ≤ 3 ms entity-tick per tick on the 1.21.1
  baseline cell; scenario + per-cell budgets owned by sub-13's TCK performance
  scenario; sub-08 supplies the counters (Stage F).

## 5. Verification

- Golden tick-trace TCK (Stage B) — determinism, every cell, headless.
- Multipart damage-zone TCK (Stage D) — multipliers, flinch, break, sync.
- Riding roundtrip TCK (E); territory/herd TCK (F).
- Perf counters feed sub-13's N-multipart performance scenario.
- Every surface ships only with its TCK scenario passing on ≥2 drivers, one
  per loader family (§8).

## 6. Agent guidance

- **Conventions:** per `docs/TEMPLATE.md` §6 — package roots
  `dev.vineengine.vine.entity|.brain` (public) / `dev.vineengine.vine.internal.*`
  (SPI/drivers); Java 21 bytecode shared, Java 25 in 26.x drivers.
  Composition over inheritance: custom `Node` types register via sub-02
  extension-point registries, never subclass `VineBrain`.
- **Forbidden (this file's emphasis):** mapping `VineBrain` to native
  goal/`Brain` systems; native AI types crossing the SPI boundary; live
  navigation inside golden-trace determinism tests.
- **Done means:** per template — all stages ticked, acceptance green on all
  covered cells, status `done`, dashboard row updated in `docs/README.md`.
