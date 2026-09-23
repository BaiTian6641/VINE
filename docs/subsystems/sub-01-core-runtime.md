# SUB-01 — Core runtime (boot, events, probes)

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 skeleton → M1 full · **Depends on:** SUB-00 · **Blocks:** SUB-02, SUB-03, SUB-05, SUB-06, SUB-16, SUB-18
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.1, §5.8, §5.9, §5.12, §8, §11.4 · **Module(s):** vine-api · vine-core · vine-spi · drivers

## 1. Purpose

The engine's heartbeat: normalized boot phases independent of loader lifecycle,
the engine event bus every subsystem uses, the §5.8 hook-family translation
table, feature probes behind `VineEngine.supports(...)`, the `@VineUnsafe`
escape hatch, the extension-point registry core all later extension points
reuse, and engine config. Source of truth for the shared-vocabulary terms
`VineEngine.supports(...)` and `@VineUnsafe`. Non-goals: descriptors/registries
(sub-02), persistence (sub-03), codecs (sub-05), per-cell hook depth
(sub-18/19 fill the driver side).

## 2. Design

- **API surface** (`vine-api`, package `dev.vineengine.vine`):

```java
public enum EnginePhase { VINE_BOOT, REGISTRIES_OPEN, REGISTRIES_FROZEN, WORLD_LOAD, SERVER_UP }
public enum EventPriority { FIRST, EARLY, NORMAL, LATE, LAST }
public interface VineEvent { }
public interface Cancellable extends VineEvent { boolean isCancelled(); void cancel(); }
public interface Subscription extends AutoCloseable {
    @Override void close();      // idempotent; may dorm the hook (Minimal Footprint)
    boolean isActive();
}
public interface EventBus {
    <E extends VineEvent> Subscription subscribe(Class<E> type, Consumer<E> handler);
    <E extends VineEvent> Subscription subscribe(Class<E> type, EventPriority priority, Consumer<E> handler);
    <E extends VineEvent> E post(E event);   // synchronous; returned for cancel inspection
}
/** Open probe type: each subsystem declares its own Feature constants. */
public interface Feature { String id(); }    // String, not VineId — sub-02 lands later
public interface VineEngine {
    static VineEngine get() { /* ServiceLoader-backed singleton via internal */ }
    EnginePhase phase();
    boolean supports(Feature feature);
    EventBus events();
    Subscription onPhase(EnginePhase phase, Consumer<PhaseChange> handler);
    ExtensionPoints extensions();
    VineConfig config();
    record PhaseChange(EnginePhase entered) implements VineEvent { }
}
```

  `@VineUnsafe` (§5.1): `@Target({TYPE, METHOD, CONSTRUCTOR, FIELD})`,
  `@Retention(CLASS)` (survives into bytecode so tooling greps it without
  classloading), element `String reason() default ""`. Consumer jars carrying
  it MUST declare `Vine-Unsafe: true` in `MANIFEST.MF`; vine-core checks
  flag-vs-bytecode consistency at boot and warns on mismatch. Released-mod
  policy locked 2026-09-23 (§11.2): allowed with manifest flag; tooling warns.
- **Player facade:** sub-01 owns `VinePlayer`, the engine's version-free
  player facade — the ONLY player-facade name in the docs; every other
  subsystem references it, never redefines it. Signature sketch (plain
  JDK/String ids only — sub-02 lands later):

```java
public interface VinePlayer { java.util.UUID uniqueId(); String name(); }
```

- **Internals** (`vine-core`, `dev.vineengine.vine.internal.core`):
  - `PhaseMachine`: strictly ordered; phases are *states* — late `onPhase`
    subscribers get an immediate replay, so loader init-order differences can
    never strand a consumer.
  - `EngineEventBus`: per-type lists sorted by priority then registration
    (stable). **Error isolation**: a throwing handler is caught, logged with
    the subscriber's owner id, bus continues; a driver error hook may
    escalate. `cancel()` gates only not-yet-run handlers of `Cancellable`
    events; nothing unwinds.
  - **Minimal Footprint**: each hook-family event maps to a `HookSlot`; the
    driver installs the native listener on first subscribe, uninstalls on
    last close. Zero consumers ⇒ zero native listeners ⇒ vanilla (§5.1).
  - `FeatureMatrix`: immutable driver-reported feature-id set behind
    `supports`.
  - `ExtensionPoints` core: `<E> ExtensionPoint<E> create(String id,
    Class<E> type)` / `get(String id, Class<E> type)`; `ExtensionPoint`:
    `register(E)` / `List<E> all()` / `stream()`. Every later extension point
    (capabilities, quest types, …) reuses this.
  - `VineConfig`: typed getters over `config/vine/engine.toml` + reload event.
    §11.4 resolved 2026-09-23: vine-core shades + relocates its dependencies
    (`dev.vineengine.internal.shaded`); Stage E's minimal internal TOML reader
    stays acceptable — either way jars are self-contained, API untouched.
- **Driver contract** (`vine-spi`, `dev.vineengine.vine.internal.spi`):

```java
public interface VineDriver {
    CellInfo cell();                   // dataVersion, loader family, feature-id set
    void bootstrap(DriverContext ctx); // common init: bus wiring, hook slots, config
    interface DriverContext {
        EventBus bus();
        void advancePhase(EnginePhase next);
        void installHook(HookSlot slot, Runnable install, Runnable uninstall);
        void reportUnsafe(Set<String> annotatedOwners);
    }
    record CellInfo(int dataVersion, LoaderFamily loader, Set<String> features) { }
    enum LoaderFamily { NEOFORGE, FABRIC }
}
```

  vine-core loads exactly one driver via `ServiceLoader`
  (`META-INF/services/dev.vineengine.vine.internal.spi.VineDriver`); zero or
  ≥2 ⇒ explicit boot failure. The driver reads `SharedConstants` **data
  version** (never version strings, §5.12) plus runtime probes (class/method
  presence) to build `CellInfo`; out-of-window ⇒ clean boot failure listing
  supported cells. **Entrypoints**: NF = `@Mod("vine")` calling `bootstrap`
  from common setup; Fabric = `ModInitializer` + `ClientModInitializer`/
  `DedicatedServerModInitializer` splits. **Dist separation**: engine core is
  dist-agnostic; client surfaces activate only from the driver client init
  path — a dedicated server never loads client classes.
- **Hook-family translation table** (§5.8) — one engine event per hook; each
  driver translates its loader's source once, inside the driver (§5.9):
  - **entity** (spawn, tick, damage pre/post, death, interact, loot,
    target-change): NF `EntityJoinLevelEvent`, living tick,
    `LivingDamageEvent.Pre/Post`, `LivingDeathEvent`, interact event, loot
    modifiers, `LivingChangeTargetEvent` · Fabric `ServerEntityEvents`,
    tick callbacks, `AttackEntityCallback`,
    `ServerLivingEntityEvents.AllowDamage/AfterDamage/AfterDeath`,
    `LootTableEvents.MODIFY`, `ServerEntityCombatEvents`,
    `UseEntityCallback`; Mixin only for target-change (no Fabric event).
  - **item** (use, tick, craft, repair, durability-change): NF
    `RightClickItem`, inventory tick, `ItemCraftedEvent`, anvil + durability
    events · Fabric `UseItemCallback`, inventory tick, crafted via Mixin
    (Fabric has no direct crafted callback; NF's `ItemCraftedEvent` does),
    anvil/durability Mixins.
  - **player** (login, logout, clone/respawn, dimension-change): NF
    `PlayerLoggedIn/OutEvent`, `PlayerEvent.Clone`,
    `PlayerChangedDimensionEvent` · Fabric `ServerPlayConnectionEvents`,
    `ServerPlayerEvents.COPY_FROM`, `ServerEntityWorldChangeEvents`.
  - **block** (place, break, neighbor-change): NF `EntityPlaceEvent`,
    `BreakEvent`, `NeighborNotifyEvent` · Fabric place/break callbacks,
    `PlayerBlockBreakEvents`, neighbor Mixins.
  - **world** (load, unload, tick, weather-change): NF `LevelEvent.Load/
    Unload`, `LevelTickEvent`, weather via level tick · Fabric
    `ServerWorldEvents`, `ServerTickEvents`, weather Mixins.

  Priority parity: NF's five priorities map 1:1 onto `EventPriority`; Fabric
  uses ordered phases (`Event.addPhaseOrdering` — NORMAL = `DEFAULT_PHASE`,
  FIRST/LAST = explicit before/after phases).

## 3. Stages

### Stage A — phase machine + engine facade + driver loading (M0)

- [x] **Do:** `EnginePhase`, `VineEngine` facade (ServiceLoader singleton),
  replaying `PhaseMachine`, `VineDriver` SPI + `CellInfo`, `SharedConstants`
  data-version self-check with explicit unsupported-cell boot failure; 1.21.1
  NF + Fabric entrypoints advancing to `SERVER_UP`.
- **Acceptance:** both 1.21.1 servers boot with the five phase transitions
  logged in order; a subscriber registered after a phase still receives its
  replay (testmod probe).
- **Touches:** vine-api (`EnginePhase`, `VineEngine`), vine-core, vine-spi,
  both 1.21.1 drivers.
- **Bootstrap prompt:**
  > Implement sub-01 Stage A on the sub-00 skeleton per §2 contracts, in
  > packages `dev.vineengine.vine` / `...internal.core` / `...internal.spi`:
  > `EnginePhase`, `VineEngine` facade (exactly one `VineDriver` service or
  > explicit boot failure), replaying `PhaseMachine`, `CellInfo` from
  > `SharedConstants` data version (§5.12), NF `@Mod` + Fabric
  > `ModInitializer` advancing all phases. Acceptance per the file's Stage-A
  > check. Conventions: docs/README.md.

### Stage B — event bus (M0)

- [x] **Do:** §2 event API + `EngineEventBus` (priority-then-registration
  ordering, error isolation with owner-tagged logging, cancel semantics);
  `PhaseChange` routed through the bus.
  - **Landed 2026-09-24:** `EventPriority`/`Cancellable`/`EventBus` +
    `VineEngine.events()`; `EngineEventBus` (insertion-sorted per type,
    snapshot per post, owner derived from the first non-engine stack frame,
    RuntimeException isolation); `PhaseMachine.onTransition` posts every
    `PhaseChange` to the bus before the replaying one-shot subscribers.
  - **Evidence:** build/sub01b harness — 9 checks green (FIRST→LAST + stable
    within priority, throwing handler doesn't stop later handlers, cancel
    gates only remaining, close idempotent + immediate, post snapshot,
    exact-type routing); `:vine-tck:bootSmoke` PASS on both 1.21.1 cells.
    The harness (not a JUnit source set) is the acceptance proxy: the repo has
    no test infrastructure yet; it is deterministic and exits non-zero on any
    failed check.
- **Acceptance:** vine-core unit tests prove FIRST→LAST ordering (stable
  within priority), a throwing handler doesn't stop later handlers,
  `cancel()` gates only remaining handlers; both drivers still boot.
- **Touches:** vine-api events, vine-core `EngineEventBus`.
- **Bootstrap prompt:**
  > Implement sub-01 Stage B: the §2 event API + `EngineEventBus` in
  > vine-core (per-type lists sorted by `EventPriority` then registration;
  > handler exceptions caught + owner-logged, bus continues; `cancel()` gates
  > remaining handlers only). Route `PhaseChange` through the bus. Acceptance:
  > unit tests pin ordering/error/cancel as observable behavior; 1.21.1
  > drivers boot unchanged.

### Stage C — hook families + lazy install (M0 → M1)

- [ ] **Do:** vine-api event records for the §5.8 families (version-free
  payloads only); `HookSlot` first-subscribe-install / last-close-uninstall in
  vine-core; 1.21.1 driver translation per the §2 table.
- **Acceptance:** TCK scenario: one hook per family fires with identical
  observable order and cancel semantics on both 1.21.1 drivers; zero-consumer
  boot installs zero native listeners (driver debug counter).
- **Touches:** vine-api hook events, vine-core `HookSlot`, both 1.21.1 drivers.
- **Bootstrap prompt:**
  > Implement sub-01 Stage C: vine-api hook-family event records per the §2
  > table (version-free payloads, Prime Invariant); vine-core `HookSlot` with
  > lazy install/uninstall (Minimal Footprint §5.1); driver translation of NF
  > events / Fabric callbacks per the table (NF priorities 1:1, Fabric ordered
  > phases). Acceptance: the file's cross-loader TCK scenario + zero-listener
  > zero-consumer boot.

### Stage D — feature probes + `supports()` (M1)

- [x] **Do:** `Feature` + `FeatureMatrix`; drivers populate ids via runtime
  probes (class/method presence, data-version ranges); seed ids
  `hasDataComponents`, `dataDrivenRegistries`, `unobfuscatedRuntime`,
  `vulkanRenderer` (+ client placeholders, §5.11); out-of-window boot failure.
  - **Landed 2026-09-24:** `Feature.of(id)` + `VineEngine.supports(Feature)`;
    vine-core `FeatureMatrix` filled once from the driver-bound
    `CellInfo.features()` (inert engine supports nothing; ids, never version
    strings). Driver probes were already in place
    (`driver-1.21.1-common/CellProbes`: data components, Brigadier, data-driven
    registries true; unobfuscatedRuntime, vulkanRenderer false) with
    `CellWindow` owning the data-version window and the explicit
    `UnsupportedCellException` boot failure.
  - **Evidence:** `build/sub01d` harness — 10 checks green (id-based matching,
    1.21.1 acceptance pair, empty matrix, forced out-of-window failure message
    naming driver + probe + window); new `vine_test:features` TCK scenario
    asserts the live pair on both 1.21.1 cells — 9/9 scenarios PASS on
    NeoForge and Fabric.
  - **Deviation:** the probe matrix's seed values are compile-time cell
    constants today (documented in `CellProbes`); cells whose values can vary
    replace them with class/method-presence probes when they land.
- **Acceptance:** on 1.21.1: `supports(unobfuscatedRuntime)` false,
  `supports(hasDataComponents)` true; `hasDataComponents` asserts TRUE on
  every cell including 26.x when it lands (Data Components exist everywhere,
  §5.4) — the matrix distinguishes cells via `unobfuscatedRuntime`,
  `vulkanRenderer`, and data-version ranges; forced out-of-window data
  version produces the explicit cell-list failure.
- **Touches:** vine-api (`Feature`), vine-core (`FeatureMatrix`), vine-spi,
  driver `CellInfo` construction.
- **Bootstrap prompt:**
  > Implement sub-01 Stage D: `Feature` (open interface, String id),
  > `VineEngine.supports(Feature)`, vine-core `FeatureMatrix` fed by driver
  > runtime probes (never parsed strings, §5.12) with the four seed ids;
  > out-of-window runtime ⇒ clean boot failure naming supported cells.
  > Acceptance: the file's probe assertions on live 1.21.1 cells + a forced
  > failure case.

### Stage E — `@VineUnsafe`, extension points, engine config (M1)

- [ ] **Do:** `@VineUnsafe` (per §2) + boot-time bytecode scan of consumer jars
  + `Vine-Unsafe` manifest-flag consistency check; `ExtensionPoints`/
  `ExtensionPoint` core; `VineConfig` (typed getters,
  `config/vine/engine.toml`, reload event).
  - **Progress 2026-09-24:** `VineConfig` landed as a static facade (same
    pattern as `VineData`/`VineCommands`) with vine-core's `ConfigService`
    (minimal TOML subset, lazy load, reload listeners, malformed-line
    tolerance) and a 13-check throwaway harness green; the `VineEngine.config()`
    accessor ships with `ExtensionPoints` so the facade stays the only public
    surface until Stage E fully lands.
- **Acceptance:** annotated jar without the flag (and vice versa) triggers the
  boot warning; two independent extension points register/list without
  interference; a config value round-trips across restart on both 1.21.1
  drivers.
- **Touches:** vine-api (`VineUnsafe`, `ExtensionPoints`, `VineConfig`),
  vine-core (scanner, registry, config), drivers (scan wiring).
- **Bootstrap prompt:**
  > Implement sub-01 Stage E: `@VineUnsafe` (TYPE/METHOD/CONSTRUCTOR/FIELD,
  > CLASS retention, optional reason); vine-core boot scan of consumer jars'
  > bytecode (no classloading) checking `Vine-Unsafe: true` both ways;
  > `ExtensionPoints` core (create/get/register/all/stream) reused by every
  > later extension point; `VineConfig` on `config/vine/engine.toml` with a
  > minimal internal TOML reader (§11.4 unresolved — parser swappable).
  > Acceptance per the file's Stage-E checks.

## 4. Problems & blockers

- **Loader boot-phase ordering** — NF mod-construction vs. Fabric
  `onInitialize` fire at different lifecycle moments; registry freeze timing
  differs. Mitigation: phases are replaying states; drivers pick the native
  anchor per phase and document it in code comments.
- **Client-only vs. server-only init** — a dedicated server must never load
  client classes. Mitigation: driver contract splits common/client/dedi init;
  sub-00's headless boot smoke on every cell catches client leaks.
- **Event priority parity** — NF has five priorities + `receiveCanceled`;
  Fabric has ordered phases and per-callback cancel conventions; Fabric-side
  Mixins (target-change, neighbor, weather) need ordering discipline. Mitigation: 1:1
  priority map + explicit phase ordering; hook-family TCK asserts identical
  observable order cross-loader (§8: ≥2 drivers, one per family).
- **`SharedConstants` access per cell** — data-version lookup differs per
  loader/version. Mitigation: per-driver probe helper; failure mode is the
  designed explicit boot failure, not silent misfire.
- **Config shading (§11.4)** — resolved 2026-09-23: shade + relocate under
  `dev.vineengine.internal.shaded`. Stage E's internal reader stays; a shaded
  NightConfig may replace it later — internals only, API untouched.

## 5. Verification

Owns TCK scenarios: **boot phases** (ordered transitions + late replay, both
1.21.1 drivers), **hook families** (one per family, identical cross-loader
order + cancel semantics), **feature probes** (expected true/false matrix per
cell via `supports()`), **minimal footprint** (zero-consumer boot installs
zero native listeners). Per §8, each ships only when green on ≥2 drivers, one
per loader family — met by the two 1.21.1 drivers at M0; 26.x re-runs the same
scenarios on landing (sub-19). No golden fixtures; dormant hook slots are
literal no-ops, bus dispatch unmeasurable on an empty tick.

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.*` (public) /
  `dev.vineengine.vine.internal.*` (SPI, drivers); shared modules Java 21
  bytecode, 26.x drivers Java 25; composition over inheritance; descriptors
  are data. `VineId` belongs to sub-02 — sub-01 APIs use plain `String` ids
  and must not depend on sub-02 types.
- **Comment policy:** javadoc on public API stating *why* + invariants;
  driver comments explain the version/loader difference absorbed (why this
  phase anchors to that loader event); no narration of the obvious.
- **Forbidden:** version-string parsing (§5.12); loader classes in API
  signatures; Mixins outside drivers; global vanilla behavior changes (§5.1);
  implementation-pinning tests.
- **Done means:** all checkboxes ticked, acceptance checks green on all
  covered cells, header `done`, dashboard row updated in `docs/README.md`.
