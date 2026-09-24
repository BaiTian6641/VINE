# SUB-21 — TCK harness

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 + continuous · **Depends on:** SUB-00 · **Blocks:** every API-surface merge (§8 rule); the M0 gate jointly with SUB-18 + SUB-22
> **Cells:** all (M0: 1.21.1 only; 26.x joins at M4) · **Loaders:** both
> **Master plan:** §7 (primary), §5.10, §6, §8, §10 · **Module(s):** vine-tck

## 1. Purpose

`vine-tck` **defines** "VINE works on version X" (§7): it boots every cell
headlessly, runs the testmod's scenario suite, and diffs results against
golden fixtures — the enforcement mechanism behind the §8 merge rule (no
surface ships without its scenario green on all cells, ≥2 drivers across
loader families). Non-goals: consumer-mod testing, driver unit tests, client
automation (manual smoke checklist until M2–M3 surfaces, §7).

## 2. Design

- **Layout:** `vine-tck/tck-core` (Java 21, `dev.vineengine.vine.internal.tck`)
  holds the DSL, runner, result model, fixture store, differ — loader-free so
  scenarios stay portable. Thin per-cell adapters beside each driver bridge
  into GameTest + command registration; the only loader-aware code.
- **Scenario DSL** — declarative records in `tck-core` (inside `vine-tck`),
  vine-api-only. SUB-22's testmod ships its scenarios as **data** (JSON
  resources), never as DSL code — the TCK parses them at discovery, so the
  testmod's consumer classpath stays pure (sub-22's purity gate applies to
  consumer code, not to tck-internal DSL):

```java
public record TckScenario(VineId id, List<TckStep> steps, Set<TckTag> tags) {}
public sealed interface TckStep permits PlaceBlock, SpawnEntity, AdvanceTicks,
    SendPacket, RunCommand, SaveReloadWorld, AssertData, AssertTrace,
    AssertTickBudget { /* typed fields per step */ }
public enum TckTag { SMOKE, PERSISTENCE, NETWORK, BRAIN, ANIMATION, PERF, QUARANTINED }
```

- **Determinism:** fixed world seed per suite; tick-locked stepping (runner
  drives the server tick loop; no wall-clock waits); seeded RNG capture at
  scenario start. Result: JSON-lines `{scenario, cell, steps, assertions,
  ticks, outcome}`.
- **Harness duality:** GameTest (all cells, 1.21.1+) is primary;
  `/vinetck run <id|all>` on the dedicated server is an equal-rank fallback
  emitting the identical result file — CI never cares which path ran.
- **Golden fixtures:** pinned world saves, `VoxelData` binaries, `VineBrain`
  tick traces, animation timing fixtures (§5.14). Core check: cross-cell
  round-trip — a 1.21.1-written save reads identically on 26.x (engine
  datafix, §7) and vice versa. Diffing compares **normalized canonical
  dumps**, never raw bytes.
- **CI:** GitHub Actions, one job per cell (JDK 21 on 1.21.1, 25 on 26.x —
  §5.10): build driver+testmod → headless boot → suite → results + fixture
  diffs as artifacts → aggregate gate job.

## 3. Stages

### Stage A — Skeleton + headless boot on both 1.21.1 cells

- [x] **Do:** `tck-core` module; per-cell boot scripts launching the
  dedicated server with driver+testmod, asserting SUB-01's phase `SERVER_UP`
  marker line (the engine-ready boot marker: the fifth phase-transition log
  line, sub-01 Stage A); GH Actions workflow, 2 jobs × JDK 21.
- **Acceptance (M0 gate, with SUB-18 + SUB-22):** CI boots dedicated servers
  on **both 1.21.1 loaders running the identical testmod source build**,
  green; a deliberately broken driver commit fails CI.
- **Touches:** `vine-tck/`, `.github/workflows/tck.yml`.
- **Bootstrap prompt:**
  > Execute sub-21 Stage A (plan §5.0/§5.10/§7/§10; `docs/README.md`
  > conventions). Create `vine-tck/tck-core` (Java 21,
  > `dev.vineengine.vine.internal.tck`), headless dedicated-server boot
  > scripts for 1.21.1-NeoForge + 1.21.1-Fabric, GH Actions matrix (JDK 21),
  > per the acceptance above; prove failure detection with a temporary break.
  > No version-string parsing — cell identity is build-time (§5.12).

### Stage B — Harness duality + scenario DSL + discovery

- [ ] **Do:** result JSON-lines model; per-cell adapters wiring scenarios
  into GameTest batches **and** `/vinetck run <id|all>` (identical output);
  DSL records (§2, exact names); boot-time discovery of the testmod's
  scenario resources (JSON data, §2); executors for the §10 M0 content set
  (registration Java+JSON, place/break, save/reload, packet echo, command) —
  capability and recipe executors land with their M1/M3 surfaces.
- **Acceptance:** testmod-declared M0 scenarios green via both harness paths
- **Landed (in-process path):** `/vine_test tck_run <id|all>` runs the *same
  scenario files* inside the running server through a testmod-side interpreter
  (a dependency-free JSON reader plus the step executors), printing the same
  `[TCK] scenario <id>: PASS|FAIL` verdict lines as the external runner — a
  scenario's meaning stays in its file, not in which harness ran it. Its reach is
  narrower by construction: it has only what a running server exposes, so
  restart-, packet- and world-shaped steps report `SKIP (<step> needs the external
  runner)`, and a trace assertion it cannot serve (a cell's console feedback
  travels that cell's logging stack, which the external runner reads from the
  process) reports `SKIP (trace not captured in-process: cell feedback tee
  pending)` instead of blaming the scenario. Both halves are asserted live:
  `harness_duality` runs a scenario in-process (verdict `PASS`), then runs one
  whose assertion needs the external path and sees the `SKIP` reason on both
  1.21.1 cells.
- **Landed (GameTest path, Fabric):** the cell declares a
  `fabric-gametest` entrypoint (`VineGameTest`) whose batch runs the *same*
  scenario files through the *same* engine-side interpreter the
  `/vine_test tck_run` command uses — `runGametest` reports "Running test batch
  'vine_tck:0' (1 tests) … All 1 required tests passed". That is the third
  harness path, and it exists because the interpreter was moved into
  `vine-api`'s internal package: the testmod compiles against `vine-api` alone
  and a driver cannot depend on a consumer, so the one shared implementation had
  to sit where both can reach it.
  **Blocked (GameTest path, NeoForge):** `@GameTest` resolves a structure template
  through the server's datapack, and NeoForge's dev run ships none — Fabric's API
  works around this by bundling its own `empty.snbt` and injecting a structure, so
  the same file placed in the mod's data folder is *not* found (`Missing test
  structure: vine:vinegametest.empty`, tried as `structure/`, `structures/`,
  dotted and slashed names, and in the correct 1.21 NBT shape). The class and run
  config were reverted rather than shipped failing; the scenario coverage is
  unaffected (both cells run the full suite through the external runner and the
  in-process path).
  **Remaining:** the per-cell feedback tee (wrap the console source so command
  feedback reaches the in-process sink) and NeoForge's GameTest structure
  provisioning — both are *additional* harness reach over the same DSL, not gaps
  in the DSL itself.
- **Landed (partial):** the scenario runner executes the documented step
  set (commands, traces, block placement, data probes, packets, save/reload,
  file writes) on both cells with a cross-cell matrix + quarantine policy from
  Stage D; `AssertTrace` now matches needles as an ordered subsequence over log
  *characters*, so several needles describing one output line (a value and its
  verdict) all match — the line-granular cursor silently rejected them, which is
  what made multi-fact assertions look flaky.
  on both 1.21.1 cells; new scenarios need zero `tck-core` edits; loader
  quirks logged in §4.
- **Touches:** `tck-core`, driver cell adapters, testmod discovery hook.
- **Bootstrap prompt:**
  > Execute sub-21 Stage B (A done). Result model + GameTest and
  > `/vinetck run` adapters emitting identical files; DSL records from §2 in
  > `tck-core` (Java 21, vine-api only — Prime Invariant holds here too);
  > boot-time discovery from SUB-22's testmod; M0 step executors. Acceptance
  > as above; record every GameTest headless quirk hit in §4.

### Stage C — Determinism core + golden fixtures + round-trip

- [ ] **Do:** fixed-seed worlds; tick-locked `AdvanceTicks`; seeded RNG
  capture; `fixtures/` store; canonical normalized dumps + differ; cross-cell
  round-trip scenario (write 1.21.1 → read 26.x and reverse; 26.x half
  activates with SUB-19).
- **Acceptance:** two consecutive CI runs byte-identical; 1.21.1-NF ↔
  1.21.1-Fabric fixture round-trip green today as differ proof.
  - **Progress 2026-09-24:** the 1.21.1-NF ↔ 1.21.1-Fabric round-trip half is
    green: `:vine-tck:crossCellRoundTrip` drives NF `tck_fixture_write` →
    Fabric `tck_fixture_read` in one JVM, comparing file digests (byte
    identity) plus per-section semantics (VoxelData/capability/session).
    Fixed seeds, tick-locked `AdvanceTicks`, canonical differ, and
    repeat-run byte-identity remain open.
- **Touches:** `tck-core`, `fixtures/`, driver tick hook.
- **Bootstrap prompt:**
  > Execute sub-21 Stage C. Fixed seed, tick-locked stepping, seeded RNG —
  > zero wall-clock in execution. Canonical dumps + differ (never raw bytes).
  > Round-trip per §7, cells discovered not hardcoded. Acceptance as above.

### Stage D — CI reporting + flaky policy

- [x] **Do:** aggregate job rendering a per-scenario × cell matrix with
  history; fixture diffs as failure artifacts; quarantine as code: two
  consecutive identical-seed failures ⇒ auto-tag `QUARANTINED`, gate
  exemption, `QUARANTINE.md` ledger entry (owner + expiry); quarantine count
  is a reported metric.
  - **Landed 2026-09-24:** the runner journals every outcome to
    `results/<cell>.jsonl` (`run`, `cell`, `scenario`, `status`, `durationMs`,
    `failure`) and writes a failure artifact per red scenario (failure detail +
    the last 40 console lines). `ResultReporter` (`:vine-tck:tckReport`) renders
    `reports/matrix.md` + `matrix.json` (per-scenario × cell with status
    history), computes quarantine (two consecutive failures of the same
    scenario — any cell), writes the gate decision
    (`results/quarantine.json`) and maintains `vine-tck/QUARANTINE.md` with
    owner + expiry; the quarantine count is printed with the report. The runner
    reads the decision before each run: quarantined failures print as
    `QUARANTINED`, are journaled, and do **not** gate. Drill scenario sets ride
    `-Pvine.tck.scenariosDir=<dir>` (a `scenarios/` dir), so the flaky policy
    itself is testable in ~2.5 minutes instead of a full suite.
  - **Evidence — the full drill, then reverted:** (1) a forced failing scenario
    goes red (`BUILD FAILED`) with journal + `artifact-<cell>-<scenario>.txt`;
    (2) the second identical run goes red, then `tckReport` reports
    `quarantined=1` and writes the decision + a ledger row
    (`owner: unassigned, expires: <now+14d>`); (3) the next run prints
    `1 scenario(s) quarantined`, reports the scenario as `QUARANTINED`, and the
    gate **passes** (`2/2 scenarios passed`). Removing the drill restores an
    empty ledger (`| _(none)_ |`) and the real suite runs green afterwards.
  - **Bugs the drill caught:** the journal writer emitted a stray quote
    (`"durationMs":90062"`) so every line failed to parse — surfaced by the
    reporter's new unreadable-line diagnostic rather than silently reporting
    "0 scenarios"; the runner read the quarantine decision from the wrong
    directory; and quarantined failures still incremented the gate tally, so the
    exemption had to be applied to the tally itself (a scenario can be
    quarantined and still fail the build only if the policy says so).
- **Acceptance:** a forced failing scenario goes red with diff artifact, then
  quarantines on the second run with ledger entry; revert afterwards.
- **Touches:** workflows, `tck-core` report renderer, ledger.
- **Bootstrap prompt:**
  > Execute sub-21 Stage D. Aggregate per-cell results into a matrix report;
  > diffs as artifacts; quarantine + ledger mechanics per §2/§4. Demonstrate
  > with a forced failure, then revert. Acceptance as above.

### Stage E — Performance harness (activates M2–M3; needs SUB-08/13 spikes)

- [ ] **Do:** tick-time sampling (per-tick ms, percentiles) + `AssertTickBudget`
  step; the §5.18/§7 scenario — N large multipart entities + herds in a
  seeded overworld; per-cell budgets in config, ratchet-only downward.
- **Acceptance:** green at baseline on all present cells; injected 10×
  slowdown fails the assert.
- **Touches:** `tck-core`, testmod perf fixture, per-cell budget configs.
- **Bootstrap prompt:**
  > Execute sub-21 Stage E (SUB-08 multipart + SUB-13 population at spike
  > level). Tick-time sampling + AssertTickBudget; N-multipart + herds
  > scenario (§5.18) with per-cell budgets, ratcheted down only. Acceptance
  > as above.

## 4. Problems & blockers

- **MDG runServer stdin (NeoForge 21.1.251, ModDevGradle):** `stop` over
  stdin does not reach the dev server through the harness's nested
  `gradlew --no-daemon` chain — the graceful-stop grace window expires and
  the runner falls back to a process-tree kill. Loom 1.17.21 forwards stdin
  and stops gracefully (exit 0). Boot assertions are unaffected (markers are
  log-watched); the kill fallback is the designed stop path, but NF cells
  pay up to `stop-grace-seconds` (default 90s) extra per run until a
  working stdin path is found.
- **Nested-build file locks (Windows):** the boot-watch JVM must not carry
  vine-api/vine-core jars on its own classpath — Windows file locks then
  break the nested driver build's `jar` tasks ("Unable to delete file").
  `vine-tck` therefore depends only on `tck-core` (pure Java); engine deps
  for scenario execution arrive with Stage B's in-server harness, not the
  process launcher. The nested build also runs with its own
  `--project-cache-dir` to avoid the outer build's project lock.
- **CI workflow pending:** `.github/workflows/tck.yml` (Stage A's GH Actions
  matrix, 2 jobs × JDK 21) is owned by sub-00's CI/publishing stage this
  wave; the local tasks (`:vine-tck:bootSmoke<Cell>`, aggregate
  `:vine-tck:bootSmoke`) are the exact commands those jobs will run.

- **GameTest headless quirks per loader** (batch timing, exit codes,
  template loading differ NF vs Fabric) — the command fallback is equal-rank
  precisely for this; quirks recorded here as found.
- **Fixture size vs git:** saves are megabytes — store compressed
  (`.tar.zst`); git holds canonical dumps + one minimal save; full saves are
  CI artifacts. Repo-policy owner: SUB-00.
- **Determinism fragility:** any wall-clock call or unseeded RNG in engine or
  testmod breaks byte-identity; Stage C's repeat-run check is the tripwire;
  violations are bugs owned by the violating subsystem, not the TCK.
- **Resolved 2026-09-24 — non-hermetic world probe (flake):** the world
  scenarios probed `[0,0,0]`, deep underground — a fluid pocket adjacent to
  the slot refilled it between the removal probe and the absence probe, so
  `air→air rejected` saw a changed block (one red run, then green twice).
  Fixed by moving both world scenarios to `[0,300,0]` (air in every world,
  no terrain/fluid neighbors) and by accepting "No chunks were marked for
  force loading" as forceload satisfaction (removes a 15 s stall per boot).
  Failures now dump the last 30 console lines (`[TCK-DUMP]`) and every boot
  persists `tck-capture-<cell>.boot<N>.log`, so flakes carry their evidence
  instead of needing a rerun.
- **CI runtime budget:** parallel per-cell jobs; PR CI runs `SMOKE` tag only,
  nightly runs all; boot time measured from Stage A onward.
- **26.x absent until M4:** machinery is cell-count-agnostic from day one
  (discover cells, never hardcode two) — review-enforced in Stages C/D.

## 5. Verification

The TCK is the verification layer; its own checks: repeat-run byte-identity
(Stage C); forced-failure drills — broken driver (A), failing scenario (D),
slowed tick (E) — each turning the right signal red; standing NF↔Fabric
1.21.1 fixture round-trip. It owns the §7/§8 merge gate: no surface ships
without its scenario green on ≥2 drivers, one per loader family, visible in
the aggregate matrix report.

## 6. Agent guidance

- **Conventions:** `tck-core` Java 21, `dev.vineengine.vine.internal.tck`;
  adapters live beside their driver at that cell's bytecode level. DSL
  signatures obey the Prime Invariant — scenarios must port to consumer mods
  unchanged.
- **Comment policy:** adapter comments name the exact loader quirk absorbed
  (+ loader version); runner comments state determinism invariants.
- **Forbidden:** version-string parsing (§5.12); wall-clock waits or unseeded
  randomness in execution; raw-byte fixture comparison; hardcoded cell
  counts; silently loosened perf budgets.
- **Done means:** M0 stages A–B green on both 1.21.1 loaders (the M0 gate);
  C–E activated as their milestone surfaces land; `done` only with the full
  4-cell matrix in CI; dashboard row updated.
