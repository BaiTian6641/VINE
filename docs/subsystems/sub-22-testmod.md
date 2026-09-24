# SUB-22 — Testmod

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 + continuous · **Depends on:** SUB-00, SUB-02 · **Blocks:** the M0 acceptance gate jointly with SUB-18 + SUB-21; M4 thesis gate with SUB-19
> **Cells:** all (M0: 1.21.1 only; 26.x joins at M4) · **Loaders:** both
> **Master plan:** §5.0, §5.1, §6, §7, §10 (M0, M4) · **Module(s):** vine-testmod

## 1. Purpose

`vine-testmod` is the consumer-style mod that exercises every `vine-api`
surface — **one of each, not exhaustive coverage** — and it *is* the Prime
Invariant proof: 100% pure vine-api source, zero version conditionals, zero
`net.minecraft`/loader references, verified by CI grep + classpath isolation.
It doubles as the TCK's scenario source (SUB-21) and as the M0/M4 acceptance
gates. Non-goals: it is not a demo mod, not a benchmark suite (beyond the
SUB-21 perf fixture), and not a dumping ground for subsystem tests — those
live with their subsystem.

## 2. Design

- **Layout** — exactly the §6 consumer shape, plain Gradle multi-project:

```
vine-testmod/
├─ src/main/java/dev/vineengine/vine/testmod/…   ← the ONLY source; pure vine-api
├─ src/main/resources/…                          ← descriptors, fixtures refs
└─ versions/
   ├─ 1.21.1-neoforge/  (deps: vine-api + vine-driver-1.21.1-neoforge)
   ├─ 1.21.1-fabric/
   ├─ 26.x-neoforge/    (added at M4)
   └─ 26.x-fabric/
```

  `versions/*` subprojects only re-link dependencies and repackage resources —
  no preprocessor plugin, no source copying (§6).
- **Purity gate** (CI, runs on every testmod change) — scope: consumer code
  in `src/main`; TCK scenario JSON/resources are **data** consumed by
  SUB-21's runner, not code (the scenario DSL lives in `vine-tck`, never on
  this classpath):
  1. `src/main/java` compiles with **only** `vine-api` + `vine-client-api` on
     the compile classpath — drivers enter at runtime only.
  2. Grep-forbidden in `src/main`: `net.minecraft`, `net.neoforged`,
     `net.fabricmc`, `Mixin`, `//?`, `@VineUnsafe` — any hit fails the build.
  3. `git diff --stat` across `versions/*/` shows no source duplication.
- **Content contract** — exactly one canonical exemplar per surface, each
  also declared as a TCK scenario (JSON data for SUB-21; §2 purity scope):

| Exemplar | Surface | Milestone |
|---|---|---|
| `testblock` + BlockEntity, `VoxelData` round-trip | sub-07, sub-03 | M0 minimal → M1 full |
| `testitem`, durability-as-data-key | sub-07, sub-03 | M0 → M1 |
| one engine event subscriber | sub-01 | M0 |
| `echo` packet (client→server→client) | sub-05 | M0 |
| `/vinetest` command | sub-06 | M0 minimal (engine command, sub-06 Stage A) → M1 full |
| capability store/retrieve on block + entity | sub-04 | M1 |
| multipart `testbeast` + `VineBrain` behavior + animation fixtures | sub-08, sub-09 | M2 |
| `testrecipe` + one custom recipe type | sub-11 | M3 |
| one quest, two objectives, one reward | sub-15 | M3 |
| one `VineSession` with late-join | sub-14 | M1 basics → M3 full |
| one HUD overlay | sub-16 | M2–M3 |

- **Feature gating:** surfaces not yet shipped are guarded by
  `if (VineEngine.supports(Feature.X)) { … }` probes (§5.12) — the testmod
  always compiles against stable API while surfaces incubate; a probe that
  stays false past its surface's milestone is a CI warning (staleness tripwire).
- **Honesty property:** the testmod MUST break when the API breaks — it pins
  no implementation, mocks nothing, and never carries compatibility shims.
  A green testmod is the API's compile-time contract with consumers.

## 3. Stages

### Stage A — Skeleton + purity gate + M0 content

- [x] **Do:** §6 layout with `versions/1.21.1-{neoforge,fabric}`; purity gate
  CI steps (classpath isolation + forbidden greps); M0 exemplars: one block,
  one item, one engine event, one packet echo, one command — the minimal
  engine command per sub-06 Stage A (the full consumer command exemplar is
  M1) — each registered
  via both Java and JSON descriptor paths (SUB-02); TCK scenario resources
  (JSON data) handed to SUB-21 discovery.
- **Acceptance (M0 gate, with SUB-18 + SUB-21):** **identical testmod source
  compiles and runs on both 1.21.1 loaders** (plan §10) — both `versions/`
  subprojects build from the same `src/main/java`, TCK boots and runs the M0
  scenarios green on both; the purity gate fails a deliberately planted
  `net.minecraft` import.
- **Touches:** `vine-testmod/`, purity steps in the TCK workflow.
- **Bootstrap prompt:**
  > Execute sub-22 Stage A (plan §5.0/§5.1/§6/§10 M0; `docs/README.md`
  > conventions). Create `vine-testmod` in the §6 consumer layout with
  > `versions/1.21.1-neoforge` + `versions/1.21.1-fabric` re-linking drivers;
  > source lives once in `src/main/java` under
  > `dev.vineengine.vine.testmod`, pure vine-api. Add the purity gate
  > (classpath isolation + forbidden-pattern grep) and the five M0 exemplars
  > with TCK scenario resources (JSON data, §2). Acceptance as above, including the
  > planted-import proof.

### Stage B — Persistence exemplars (M1)

- [ ] **Do:** `testblock` gains a BlockEntity with `VoxelData` round-trip
  (place → write → save/reload → assert equal); `testitem` durability modeled
  as a data key; capability store/retrieve on both block and entity;
  `VineSession` basics exemplar. All as SUB-21 scenarios feeding the
  golden-fixture store.
- **Landed:** `testblock` is now flagged as an engine-storage block (sub-07
  Stage C's storage half) and the placed-block probe walks the whole acceptance
  path in the world itself: place → write `payload.marks=417` → server restart →
  read back `survived=417`, asserted by `voxeldata_placed_block` on both cells.
  Durability is a data key by construction rather than by a tuning field —
  `ItemTuning` deliberately excludes it and the item payload's native mapping
  mirrors vanilla `minecraft:damage`, which the probe's native leg proves
  (`damage=7` written through the tree, read from the component). Capability
  store/retrieve covers block and entity scopes since sub-03's attach wave
  (`caps scopes ... block=31 entity=41`), and session basics are the lifecycle,
  persistence and late-join scenarios.
  **Remaining:** golden-fixture dumping of these exemplars (the cross-cell task
  covers VoxelData/registry payloads today; wiring the placed-block and
  capability payloads into it is the follow-up).
- **Acceptance:** round-trip scenarios green on both 1.21.1 cells; fixture
  dumps land in SUB-21's store and diff clean across loaders.
- **Touches:** `vine-testmod` content + scenarios.
- **Bootstrap prompt:**
  > Execute sub-22 Stage B (SUB-03 VoxelData, SUB-04 capabilities, SUB-14
  > basics available). Add the BlockEntity/VoxelData round-trip,
  > durability-as-data-key, capability store/retrieve, and session-basics
  > exemplars, each one instance, each a TCK scenario. Pure vine-api; purity
  > gate stays green. Acceptance as above.

### Stage C — Entity & animation exemplars (M2)

- [ ] **Do:** `testbeast`: one multipart entity (head/body/tail parts) with
  damage routing + per-part state persistence, one `VineBrain` behavior
  (idle → approach → attack loop) with a golden tick-trace scenario, and one
  GeckoLib-format animation driving the server evaluator with timing fixtures
  (§5.14).
- **Acceptance:** SUB-21 runs the tick-trace determinism and animation-timing
  scenarios green on both 1.21.1 cells; trace fixtures byte-identical across
  repeat runs.
- **Touches:** `vine-testmod` entity content, animation assets, fixtures.
- **Bootstrap prompt:**
  > Execute sub-22 Stage C (SUB-08 + SUB-09 at M2 level). One multipart
  > `testbeast` with part damage routing and persistence, one VineBrain
  > behavior loop, one server-evaluated animation — each exactly one
  > instance, each a TCK scenario with golden fixtures. Acceptance as above.

### Stage D — Breadth exemplars (M3)

- [ ] **Do:** `testrecipe` (shaped) + one custom recipe type (non-grid);
  one quest with two objectives and one reward; `VineSession` full —
  late-join sync exemplar; spawn-control scenario fixture if SUB-13 needs a
  consumer-side driver.
- **Acceptance:** recipe materialization, quest completion path, and
  late-join scenarios green on both 1.21.1 cells via SUB-21.
- **Touches:** `vine-testmod` content + scenarios.
- **Bootstrap prompt:**
  > Execute sub-22 Stage D (SUB-11, SUB-14 full, SUB-15 available). One
  > shaped recipe, one custom recipe type, one two-objective quest with
  > reward, one late-join session — each a single exemplar + TCK scenario.
  > Acceptance as above.

### Stage E — Client exemplar + gating audit (M2–M3)

- [ ] **Do:** one HUD overlay against `vine-client-api` (sub-16), registered
  only behind `VineEngine.supports(ClientFeature.HUD)`; audit every
  incubating-surface guard in the testmod and wire the staleness CI warning
  (probe false past its surface's milestone ⇒ warn).
- **Acceptance:** client-smoke checklist item (HUD renders, driven by server
  state) passes manually per cell; gating audit report clean in CI.
- **Touches:** `vine-testmod` client package, CI check.
- **Bootstrap prompt:**
  > Execute sub-22 Stage E (SUB-16 HUD surface landed). One HUD overlay
  > exemplar behind a `supports(...)` probe; audit all probes, add the
  > staleness warning. Acceptance as above; client smoke is manual per §7.

### Stage F — 26.x wiring + M4 thesis gate (M4)

- [ ] **Do:** add `versions/26.x-{neoforge,fabric}` subprojects re-linking the
  SUB-19 drivers; zero changes under `src/main`.
- **Acceptance (M4 gate):** the testmod **rebuilds for 26.x with zero source
  changes** (plan §10) — only `versions/` build files added; full TCK suite
  green on all four cells, including cross-cell fixture round-trips.
- **Touches:** `vine-testmod/versions/`.
- **Bootstrap prompt:**
  > Execute sub-22 Stage F (SUB-19 drivers done). Add the two 26.x
  > `versions/` subprojects. Touch nothing under `src/main` — any needed
  > source change is a bug in the API/drivers, report it instead. Acceptance:
  > M4 gate as above.

## 4. Problems & blockers

- **Honesty decay:** as the API evolves the testmod must keep breaking when
  it breaks — never patch it with shims or version-shaped workarounds; API
  breaks migrate the testmod in the same commit as the break (§8 SemVer).
- **Scope creep into a dumping ground:** the contract is *one exemplar per
  surface*; subsystem-level edge cases belong to that subsystem's own tests.
  Review rule: a testmod PR adding a second exemplar of the same surface
  needs explicit justification.
- **Stage order tied to subsystem milestones:** Stages B–E block on their
  owning subsystems (03/04/14, 08/09, 11/15, 16); use `supports(...)` guards
  so landed stages never rot while later surfaces incubate.
- **Gaming the purity gate:** greps are text-based — pair them with the
  classpath-isolation compile (step 1), which cannot be gamed because drivers
  simply are not on the compile classpath.
- **Dual registration paths:** Java + JSON descriptor exemplars must assert
  identical resulting IDs (SUB-02), else the JSON path silently diverges.

## 5. Verification

The testmod is verified *by* SUB-21 — every exemplar ships as a TCK scenario
green on ≥2 drivers, one per loader family (§8), before its owning subsystem
marks the surface shipped. Its own meta-checks: the purity gate (classpath
isolation + forbidden greps + no-duplication diff), the planted-import drill
(Stage A), the staleness probe audit (Stage E), and the two gates — M0
(identical source compiles and runs on both 1.21.1 loaders) and M4 (zero
source changes on 26.x). Client surfaces verify via the §7 manual smoke
checklist until automatable.

## 6. Agent guidance

- **Conventions:** single source root `src/main/java` under
  `dev.vineengine.vine.testmod`; the testmod compiles at the shared floor
  (Java 21 bytecode) even on 26.x cells — it is consumer code. Exemplars use
  descriptor/data paths (Java + JSON), never driver internals.
- **Comment policy:** each exemplar's javadoc names the surface it canons and
  why that shape was chosen; comments never narrate API usage mechanics.
- **Forbidden:** anything on the purity-grep list; `supports(...)` probes
  around *shipped* surfaces (probes are for incubating ones only); second
  exemplars of one surface; mocks/fakes — the testmod runs against real
  drivers or not at all.
- **Done means:** M0 Stage A gate green; later stages activated with their
  milestones; `done` only when the M4 gate passes on all four cells;
  dashboard row updated.
