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
  vanilla mechanism manually. **Verified 2026-09-26:** the NeoForge primitive is
  real (`net.neoforged.neoforge.entity.PartEntity`, with vanilla
  `EnderDragonPart` patched to extend it) and Fabric has no equivalent, so this
  asymmetry is the loader's, not a gap in the plan. Third-party datapack solutions
  in this space (e.g. CustomHitboxLib, 1.20.1 Forge / 1.21.1 NeoForge) are
  NF-only and would break Fabric parity.
- **Data flow / sync:** part positions are server-authoritative, computed by
  the sub-09 evaluator; published fixed-point-quantized, delta-compressed over
  sub-05 (deadband 0.02 blocks). Rider input: client→server over sub-05,
  validated server-side.
  **Why the evaluator and nothing else (verified 2026-09-26):** GeckoLib cannot be
  the server's collision source — its bone world positions live in a render-pass
  facility (`RenderPassInfo`) and its animation controllers are called per render
  frame, so a server-tick hitbox cannot be read out of them. The plan's own
  evaluator (§5.14, sub-09) is the design the ecosystem independently recommends:
  server-tick time base, pose evaluated from the Blockbench asset, swept collider
  between previous and current locator, and a per-attack already-hit set.
  **Decision (locked 2026-09-26): Stage D lands *after* the sub-09 evaluator**, so
  parts are bone-accurate from their first boot; declared geometry and the scripted
  pose tape remain staging devices for the interface, never the shipped source.

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
- **Landed (engine half, 2026-09-26):** `dev.vineengine.vine.entity` ships
  `EntityDescriptor(id, attributes, dimensions, parts)`, `AttributeSpec`,
  `AttributeModifier` (add / multiply-base / multiply-total), `Dimensions` and
  `PartDescriptor`, all data with codecs, registered as structural type
  `vine:entity` through sub-02's existing path — JSON authoring needed no loader
  work at all, because discovery is per registry id
  (`data/<ns>/vine/entity/<name>.json`). Spawning goes through the new engine
  surface `VineEntities.spawn(entityId, VineWorld, Vec3)`, backed by a per-cell
  `EntityDriver` bound once via `EntityBinding` (the fourth instance of the
  bind-once seam, after storage, world view and capabilities statement).
  The engine validates the descriptor's existence before the cell is asked, so a
  cell never answers for content the engine does not know.
  **Evidence (2026-09-26, both 1.21.1 cells):** the descriptors materialize and
  spawn on both cells — `vine: materialized entity vine_test:testbeast
  max_health=200.0 movement_speed=0.3 knockback_resistance=0.6` at registration and
  `vine: entity spawned vine_test:testbeast max_health=200.0 movement_speed=0.3
  knockback_resistance=0.7` at spawn, the live 0.7 being the descriptor's
  `ADD 0.1` modifier read back from the spawned entity's own attribute instance —
  with the `entity_spawn_attributes` scenario green on both cells (**37/37 each**).
  Two ordering facts this stage forced, both now documented where they belong:
  structural JSON must complete *before* a cell snapshots the structural view
  (`DescriptorStore.beforeStructuralSnapshot`), because Fabric's native registries
  freeze during mod init — without it a JSON-authored descriptor is materialized by
  nobody; and attribute ids are the cell's own, so a missing one fails the boot
  naming it rather than silently dropping an attribute.
  One fixture sharpening: the descriptor now sets `eyeHeight` explicitly (2.2 rather
  than vanilla's height × 0.85 = 2.72), so the explicit override is observably
  different from the default the cell would otherwise compute.
  Two shape notes against the §2 sketch: `ride` and `spawnRule` are *not*
  fields yet — they arrive with Stage E (riding) and Stage F (territory), and
  declaring them now would be a field nothing can exercise; and every attribute
  is named by its **native** id (`minecraft:generic.max_health` on 1.21.1),
  because attributes are the one entity surface whose registry stays the cell's
  own authority — the engine wraps values and bounds, it does not shadow the
  registry.
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
- **Landed (engine half, 2026-09-26):** `dev.vineengine.vine.brain` ships the
  sealed node vocabulary (`Node` permitting `Sequence`, `Selector`, `Parallel`,
  `ConditionLeaf`, `Action` — note `ConditionLeaf`, because the sketch's `Condition`
  name belongs to the predicate interface), `NodeStatus`, `BrainContext`,
  `Blackboard`/`BlackboardKey` (typed keys over a `VoxelData` tree, so the
  blackboard *is* the persisted tree), `Primitives` (the only way a behaviour
  reaches the world) and `VineBrain`/`VineBrains`, with the runtime in vine-core
  (`BrainImpl`, `BlackboardImpl`, `StateMachineBuilderImpl`).
  Three decisions worth recording:
  (1) the state machine **lowers to an `Action`** rather than being a node kind of
  its own — a state machine is composition (a state table plus a current-state
  cell), and keeping it out of the sealed set means no cell can special-case it;
  its current state lives under the reserved key `brain.fsm.state`, so a reloaded
  actor resumes where it was;
  (2) `TapePrimitives` — the injectable primitive-result tape — is *public API on
  purpose*: it is the instrument the determinism claim is checked with, and it can
  both capture a live run and replay it, failing loudly on the first divergence in
  the call sequence;
  (3) the blackboard's `VineId` codec collapsed sub-02's private `ContentCodecs`
  seam into `VineId.CODEC`, deleting the private helper entirely.
  The exemplar (`vine-testmod` `BrainExemplar`) runs a hunt loop
  (wander → chase, both multi-tick) through capture then replay and prints the
  call digest, the memory digest and the per-tick status trace; the TCK scenario
  pins the digests, which is what makes "identical on both cells" a checked claim.
  **Evidence (2026-09-26, both cells):** `brain_trace` green on 1.21.1-fabric and
  1.21.1-neoforge with the *same* golden values — 120 ticks, 255 primitive calls,
  call digest `199b4c04…03a9`, trace/memory digest `85021250…bef0` and
  `identical=true` between the captured run and its replay — and the exemplar's own
  capture/replay comparison runs inside each cell's boot, so the determinism claim
  is checked twice per cell: once by the engine comparing its two passes, once by
  the scenario pinning the digest. The traces also show the runtime genuinely
  suspending and resuming (multi-tick `RUNNING` runs inside both states), which is
  what a single-tick tree could not prove.
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
  threshold, positions sync within 1 tick on both 1.21.1 cells — with the positions
  coming from the sub-09 evaluator, not from a tape.
- **Fixture (locked 2026-09-26):** the wyvern lives in the testmod
  (`head/neck/body/tail/wing_l/wing_r`, per-part multipliers, flinch, tail break,
  sever) and the acceptance fight is driven by a testmod weapon whose combat owner
  is Better Combat — proving the engine's part routing and the partner bridge in
  one scenario. This wyvern is the regression fixture for sub-09 and sub-10.
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
