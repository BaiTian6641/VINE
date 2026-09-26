# SUB-18 — Driver 1.21.1 (NeoForge + Fabric)

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M0 · **Depends on:** SUB-00, SUB-01 · **Blocks:** SUB-19
> **Cells:** 1.21.1 · **Loaders:** both
> **Master plan:** §5.8–§5.10, §10 (M0) · **Module(s):** drivers

## 1. Purpose

Materialize `vine-spi` on Minecraft 1.21.1 for both loaders. Same MC version
on both cells isolates the **loader axis**: M0 passes when identical testmod
source compiles and runs on both (§10). This pair also owns the matrix's
entire mappings burden (§5.10) — remap steps and refmaps exist only here.
Non-goals: no version-axis work (sub-19), no API/SPI shape decisions
(sub-01/02 own the contract), no content semantics (drivers host, never
invent).

## 2. Design

Two subprojects under `drivers/` (sub-00 layout), packages
`dev.vineengine.vine.internal.driver1211.{neoforge,fabric}`, Java 21 bytecode,
deps `vine-core`+`vine-spi`; loader-neutral 1.21.1 mechanics in a shared
`driver1211.common` source set, never `vine-core`. Stonecutter deferred (§6).

**Per-cell differences:**

| Concern | 1.21.1-NeoForge | 1.21.1-Fabric |
|---|---|---|
| Toolchain | ModDevGradle (preferred; 26.x parity), Java 21 | fabric-loom **remapping**, Java 21 |
| Mappings | dev Mojmap+Parchment; runtime Mojmap | dev Yarn; runtime **intermediary** |
| Entrypoint | `@Mod("vine")` + mod bus; client via dist-segregated `@Mod(dist=CLIENT)` | `ModInitializer` + `ClientModInitializer` (fabric.mod.json) |
| Registration | `DeferredRegister`; design descriptors `DataPackRegistryEvent.NewRegistry` (§5.2) | `Registry.register`; design descriptors `DynamicRegistries.registerSynced` |
| Capabilities | `RegisterCapabilitiesEvent` → `Block/Item/EntityCapability` | Fabric API lookups where present; else engine fallback store (§5.5) |
| Payloads | `RegisterPayloadHandlersEvent` → `PayloadRegistrar` | `PayloadTypeRegistry` + `ServerPlayNetworking` |
| Multipart | NeoForge-formalized `PartEntity` on root entity | vanilla mechanism hosted manually (engine part list + quarantined accessor) |
| Render backend | GeckoLib **GL4**, hard dep (§5.14/§5.20) | GeckoLib **GL4**, hard dep |
| Mixins | one config + refmap (Parchment insurance) | one config + refmap (Yarn→intermediary: mandatory) |
| GameTest | `RegisterGameTestsEvent` / `@GameTest` | Fabric GameTest API (both wrap vanilla harness) |

**Event translation (§5.8 families → engine events):** sub-01 owns the engine
event types, `HookSlot` machinery, and priority parity (NF's five priorities
↔ `EventPriority`; Fabric `Event.addPhaseOrdering` with `DEFAULT_PHASE` as
NORMAL); this table fixes the concrete 1.21.1 native source per hook. Native
listeners install/uninstall via `DriverContext.installHook` on first/last
subscription (Minimal Footprint); † = no native hook → quarantined Mixin.

| Hook family | NeoForge 1.21.1 | Fabric 1.21.1 |
|---|---|---|
| entity.spawn / tick | `EntityJoinLevelEvent`, `MobSpawnEvent.FinalizeSpawn` / `EntityTickEvent.Pre/Post` | `ServerEntityEvents.ENTITY_LOAD` / † |
| entity.damage / death / loot | `LivingIncomingDamageEvent`, `LivingDamageEvent.Pre/Post`, `LivingDeathEvent`, `LivingDropsEvent` | `ALLOW_DAMAGE`/`AFTER_DAMAGE`, `AFTER_DEATH`, `LootTableEvents.MODIFY` |
| entity.interact / target | `PlayerInteractEvent.EntityInteract` / `LivingChangeTargetEvent` | `UseEntityCallback` / † |
| item.use / craft / tick | `RightClickItem` / `PlayerEvent.ItemCraftedEvent` / † | `UseItemCallback` / † / † |
| player.login/out/clone/respawn/dimension | `PlayerLoggedIn/OutEvent`, `PlayerEvent.Clone`, `PlayerRespawnEvent`, `PlayerChangedDimensionEvent` | `JOIN`/`DISCONNECT`, `COPY_FROM`, `AFTER_RESPAWN`, `AFTER_PLAYER_CHANGE_WORLD` |
| block.place/break/neighbor | `EntityPlaceEvent`, `BreakEvent`, `NeighborNotifyEvent` | `PlayerBlockBreakEvents`; place/neighbor † |
| world.load/unload/tick/weather | `LevelEvent.Load/Unload`, `LevelTickEvent`; weather † | `ServerWorldEvents.LOAD/UNLOAD`, `START_WORLD_TICK`; weather † |

**Mixin Quarantine (§5.9):** exactly one Mixin config per driver; every Mixin
backs an SPI primitive lacking a native hook and names that method in a
comment.

## 3. Stages

Each bootstrap assumes the agent has read this file, plan §5.8–§5.10, and
`docs/README.md` conventions.

### Stage A — Driver foundations on the sub-00 scaffolds
- [x] **Do:** sub-00 Stage B already scaffolded both subprojects (toolchains,
  mappings, marker entrypoints, dev run configs). Turn scaffolds into drivers:
  package roots `driver1211.{common,neoforge,fabric}` with a shared
  `driver1211.common` source set wired into both; complete loader metadata
  (full `neoforge.mods.toml` / `fabric.mod.json` incl. GeckoLib hard-dep
  declaration per §5.14); dist-segregated client init classes per §2
  (`@Mod(dist=CLIENT)` sibling / `ClientModInitializer`).
- **Acceptance:** both cells still boot to `Done` vanilla-identical; `common`
  code compiles into both jars; client classes never load on a dedicated
  server (log assertion).
- **Touches:** `drivers/driver-1.21.1-*/` sources + metadata.
- **Bootstrap prompt:**
  > Execute Stage A of `docs/subsystems/sub-18-driver-1211.md`: on top of
  > sub-00 Stage B's scaffolds, establish packages
  > `dev.vineengine.vine.internal.driver1211.{common,neoforge,fabric}`, wire
  > the shared `common` source set into both drivers, complete loader metadata
  > (GeckoLib hard dep), add dist-segregated client init per §2. Acceptance =
  > both cells boot vanilla-identical, common code in both jars, no client
  > classes on a dedicated server.

### Stage B — Core-runtime binding & probes

- [x] **Do:** implement sub-01's `VineDriver` SPI in both drivers:
  `bootstrap(DriverContext)` wiring, loader lifecycle →
  `DriverContext.advancePhase` through the full `VINE_BOOT → REGISTRIES_OPEN →
  REGISTRIES_FROZEN → WORLD_LOAD → SERVER_UP` chain; `CellInfo` with
  `SharedConstants` data version + §5.12 probe matrix
  (`unobfuscatedRuntime=false`, `vulkanRenderer=false` here) — never version
  strings; `META-INF/services` registration so vine-core loads exactly one
  driver per cell.
- **Acceptance:** boot log shows all five phases in order on both cells;
  `VineEngine.supports(...)` answers identical except loader id; data version
  outside the 1.21.1 window ⇒ sub-01's clean boot failure listing supported
  cells.
- **Touches:** `driver1211.*.boot`.
- **Bootstrap prompt:**
  > Execute Stage B of `docs/subsystems/sub-18-driver-1211.md`: implement
  > sub-01's `VineDriver` SPI in both 1.21.1 drivers — `bootstrap` wiring,
  > full five-phase `advancePhase` mapping from loader lifecycle, `CellInfo`
  > from `SharedConstants` data version + probes (never version strings),
  > ServiceLoader registration. Acceptance = phase log lines in order,
  > identical probe answers, out-of-window data version fails clean per
  > sub-01.

### Stage C — Event translation layer

- [x] **Do:** the §2 event table for all §5.8 families: lazy subscription, SPI
  ordering, † rows as Mixins in the single per-driver config.
- **Acceptance:** TCK event scenario — engine handlers on `block.place` and
  `entity.damage` fire with identical normalized payloads/order on both cells;
  zero listeners when no consumer subscribes.
- **Touches:** `driver1211.*.events`, both Mixin configs.
- **Bootstrap prompt:**
  > Execute Stage C of `docs/subsystems/sub-18-driver-1211.md`: implement the
  > §2 event-translation table in both drivers, lazy per Minimal Footprint,
  > †Mixin rows in the one config per driver. Acceptance = TCK event scenario
  > green on both cells with identical payloads.

### Stage D — Registration, capabilities, payloads

- [ ] **Do:** sub-02 descriptor materialization, capability SPI, sub-05
  payload SPI, using the §2 per-loader mechanisms.
- **Acceptance:** the M0 content set (§10: one block, one item, one engine
  event, one packet, one minimal command) materializes from one descriptor
  source, TCK green both cells. Capability store/retrieve is **M1** content
  (§10.2) — the capability SPI lands here, its content gate at M1.
- **Touches:** `driver1211.*.registry`, `.caps`, `.net`.
- **Bootstrap prompt:**
  > Execute Stage D of `docs/subsystems/sub-18-driver-1211.md`: implement the
  > sub-02 registration SPI, capability SPI, and sub-05 payload SPI in both
  > drivers using §2's per-loader mechanisms. Acceptance = the §10 M0 content
  > set from identical descriptors passes TCK on both cells; capability
  > store/retrieve content is M1 (§10.2), gated there.

### Stage E — Quarantine hardening & M0 gate

- [ ] **Do:** finalize Mixin configs + refmaps (verify against the *remapped*
  Fabric jar); GameTest smoke per loader; driver side of the joint M0 gate
  (with sub-21 harness + sub-22 testmod, which owns `vine-testmod` content).
- **Acceptance:** **M0 pass — identical testmod source (one block, one item,
  one engine event, one packet, one command) compiles and runs on both
  loaders** (§10); CI green both cells, JDK 21.
  - **Mixin status (2026-09-25):** the wave ships **one** Mixin config, in the
    Fabric driver — `vine-1211-fabric.mixins.json`, registered through
    `fabric.mod.json`'s `mixins` array — holding exactly the two slots Fabric
    has no callback for (`blockPlace`, `worldSave`; NeoForge observes both
    natively via `BlockEvent.EntityPlaceEvent` / `LevelEvent.Save`). Every other
    hook still rides documented loader APIs, and the NeoForge driver still ships
    **zero** Mixins; the quarantine rule is now "Mixin only where the loader has
    no native hook", not "no Mixins".
  - **No refmap — supersedes the §2 table row:** Loom 1.17.21 remaps a Mixin's
    annotation strings in place (`useLegacyMixinAp` default), so the built jar
    carries the config plus both Mixin classes and **no** `*refmap.json`; the
    remapped classes hold intermediary targets (`method_7712(class_1750)`,
    `method_7708(class_1750,class_2680)`, `method_14176(class_3536,ZZ)V`). §2's
    "one config + refmap (Yarn→intermediary: mandatory)" row is therefore wrong
    for this cell — verified against the artifact, not assumed.
  - **What the Mixins are:** `fabric/mixin/BlockItemPlaceMixin` wraps the call
    to the protected `BlockItem#place(ItemPlacementContext, BlockState)` inside
    the public `BlockItem#place(ItemPlacementContext)`;
    `fabric/mixin/ServerWorldSaveMixin` injects at `RETURN` of
    `ServerWorld#save(ProgressListener, boolean, boolean)` behind the same
    `!skipSave` guard the NeoForge site sits in. Both post through
    `events/MixinHookTap` (two volatile sinks, `null` while nothing
    subscribes), which `FabricHookInstallers` binds on first subscription and
    clears on last close — the engine's `installHook` contract is untouched and
    the Mixin stays invisible to it.
  - **Why minimal, and the rejected alternatives:** the public `place` is the
    seam because every item-driven placement funnels through it exactly once —
    `ItemStack#useOnBlock`, `BlockPlacementDispenserBehavior`, and the
    `BedItem`/`TallBlockItem` overrides of the protected one (beds do not call
    `super`, tall blocks do, so a Mixin on that override would either miss beds
    or report twice). Rejected: `World#setBlockState` — the global block write
    would patch every vanilla and modded write (Minimal Footprint §5.1, "no
    global vanilla behavior change"); and NeoForge's post-write rollback —
    Fabric's seam has no block-snapshot capture, and gating the application
    gives the same observable outcome (veto ⇒ no block, no item consumed,
    `FAIL`) without a second world write. For save, rejected: `saveLevel()`
    (level data only, so a skipped data write and the world-store flush that
    rides this hook would go unobserved) and the `SERVER_STOPPING`-only flush
    seam (not a save observation — it stays the coarse fallback if this Mixin is
    ever withdrawn).
  - **Ordering difference recorded:** NeoForge reports `EntityPlaceEvent` after
    the vanilla write and reverts on cancel, while this cell gates the write
    itself; the save hook, by contrast, sits at the same point of the
    `!skipSave` branch as NeoForge's. Normalized payload and veto outcome are
    identical on both cells, the intra-`place` position is not — the TCK asserts
    payload equality, not that position.
  - **Deviation recorded:** install/uninstall cannot switch a Mixin off (it is
    applied at class load), so Minimal Footprint's "zero consumers ⇒ zero native
    listeners" degenerates to "zero consumers ⇒ one null check, no event object,
    vanilla untouched".
  - **Evidence (2026-09-25):**
    `./gradlew :drivers:driver-1.21.1-fabric:runGameTestServer` (this stage's
    headless GameTest boot — Loom names run tasks from the run-config key, so
    the driver build registers `runGameTestServer` as a documented alias of
    `runGametest`) prints
    `[05:32:22] [Server thread/INFO] (Minecraft) [STDOUT]: vine-driver-mixin: hooked slots installed=2`,
    `… blockPlace world=minecraft:overworld block=minecraft:stone
    pos=4862464,-58,-10346946 player=ba943433-…`,
    `… block=minecraft:oak_door …` (a two-high block reports once),
    `… blockPlace vetoed block=minecraft:dirt`,
    `… worldSave world=minecraft:overworld`,
    `… mixin hooks ok places=2 vetoes=1 saves=1`, then
    `All 2 required tests passed :)`.
    `unzip -l build/libs/vine-driver-1.21.1-fabric-0.1.0-SNAPSHOT.jar` lists
    `vine-1211-fabric.mixins.json` and
    `fabric/mixin/{BlockItemPlaceMixin,ServerWorldSaveMixin}.class` and no
    `*refmap.json`. `./gradlew :vine-tck:runScenarios1211Fabric` reports
    **29/33**, with `hooks`, `harness_probe/duality/truncation`,
    `block_place_break`, `save_reload` and every session/voxeldata scenario
    green. Of the four failures, `state_roundtrip` and
    `determinism_tick_barrier` are the not-yet-written Fabric state half, while
    `command_tree` and `id_map_policy` are stale needles that the concurrent
    `vine_test:stateblock` content broke (the id map dump shows
    `vine_test:stateblock=4`, shifting the pinned `optional=4` to 5; the
    suggestion line now reads
    `suggestid: vine_test:stateblock,vine_test:testblock,vine_test:testitem`).
    Neither is this stage's doing: with the Mixins unregistered and only those
    two scenarios drilled, both fail with the same messages.
  - **Trigger path (driver-owned):** the testmod's hook path cannot reach these
    two slots (it posts synthetic command events and subscribes to loader-API
    slots only), so the driver owns the trigger — `tck.MixinHooksGameTest`,
    listed in `fabric.mod.json`'s `fabric-gametest` entrypoint, drives vanilla's
    own `ItemStack#useOnBlock` (`TestContext#useStackOnBlock` with a mock
    player) and `ServerWorld#save`, and asserts payload, position, UUID, the
    veto (no block, no item consumed) and the `skipSave` guard.
  - **M0-pass clause green 2026-09-24:** the same testmod source (block, item,
    registry marker, engine packet via the loopback, engine commands, hook
    events) runs on both 1.21.1 loaders — 10/10 TCK scenarios on each cell plus
    boot smoke. Remaining Stage E items: per-loader GameTest smoke (Fabric path
    above; NeoForge path pending) and the CI workflow, which rides sub-00's
    pending CI stage.
- **Touches:** Mixin configs (`drivers/driver-1.21.1-fabric/src/main/resources/
  vine-1211-fabric.mixins.json` + its `fabric/mixin` package), `vine-tck` cell
  wiring.
- **Bootstrap prompt:**
  > Execute Stage E of `docs/subsystems/sub-18-driver-1211.md`: finalize Mixin
  > configs + refmaps (NF Mojmap runtime; Fabric Yarn→intermediary), GameTest
  > smoke per loader, then run the M0 gate jointly with sub-21/sub-22:
  > vine-testmod compiles unmodified and passes TCK dedicated-server boot on
  > both cells. Report loader-axis leaks to sub-01/02 owners; never patch
  > around them locally.

## 4. Problems & blockers

- **NeoForge cell: a summoned vanilla mob is not selectable afterwards (open, measured 2026-09-26).**
  While adding the "an adjacent unregistered mob stays vanilla" check, one scripted run per cell did
  the same thing: `summon minecraft:zombie 4.5 300 0.5 {…}` then
  `execute if entity @e[type=minecraft:zombie,limit=1,sort=nearest] run say …`.

  | observed | Fabric | NeoForge |
  |---|---|---|
  | `summon` result count | 1 | 1 |
  | `@e[type=minecraft:player]`, `@p`, `@a` | match | match |
  | `@e[type=minecraft:zombie]` (same server, 20 ticks later) | matches | **matches nothing** |
  | `data get entity @e[…zombie…] Pos` | returns the zombie's position | `No entity was found` |
  | the client's crosshair during a swing | names the zombie | names the zombie |

  So on this cell the entity is created (the summon succeeds, and the client — which is connected to
  that same server — sees it well enough to aim at it), yet no selector on the server resolves it,
  with console authority or the player's. Approaches tried: player-source commands (the cell's
  original path) and console-authority commands via the new `server_command` step (both cells);
  `limit=1,sort=nearest` and unrestricted `@e[type=…]`; with and without an NBT predicate;
  20 and 60 ticks after the summon; midnight plus `Fire:-20s` to rule out daylight burning (which
  *was* real on both cells before that fix: a burning zombie read 16.0f before any punch).
  Ruled out: chunk loading (the platform `fill` returns the expected 52 blocks and the teleported
  player stands on it), selectors in general (`@p`/`@a`/players match), and the engine's hooks
  (the engine cannot address unregistered content at all — `VineCombat.strike` takes a
  `VineEntityRef` target, so an unregistered mob is unreachable by construction).

  Consequence: the Fabric cell has the runtime half of this check (vanilla punch, 20.0f → 19.06f,
  deterministic across runs, at `vine-tck/client/1.21.1-fabric-vanilla-bystander.json`); this cell's
  script of the same shape fails loudly at the first selector assertion. Not a gate on either cell
  until this is understood.


- **Two loaders, one repo, shared `vine-core`:** loom remap vs MDG straight
  compile of the same artifacts. Mitigation: sub-00 keeps shared modules
  loader-agnostic Java 21; loader plugins live only in driver subprojects.
- **Intermediary dev-vs-runtime:** Yarn dev names hide refmap failures until
  production. Mitigation: TCK runs the *remapped* Fabric jar in CI.
- **Bus-vs-callback impedance:** NF's cancellable priority bus vs Fabric's
  flat callbacks. Where ordering can't be guaranteed natively, add a †Mixin
  hook — never weaken the SPI guarantee. Owner: this file.
- **Lockstep SPI drift:** identical semantics on both drivers, enforced by the
  TCK (sub-21); divergence fails a scenario, never merges silently.
  PartEntity/GL4 wiring lands with sub-08/09 — this file fixes only the
  per-loader approach (§2).

## 5. Verification

TCK scenarios owned: dedicated-server boot per cell; event translation
(identical payload + ordering, both cells); M0 content set; Minimal Footprint
smoke; remapped-Fabric-jar boot. §8 rule: every SPI surface ships only with
its scenario green on both cells (both M0 cells mandatory →
one-per-loader-family satisfied by construction).

## 6. Agent guidance

- **Conventions:** drivers may freely use `net.minecraft.*` and loader APIs —
  they are the quarantine zone. Java 21 bytecode. Behavior composition; no
  cross-boundary class hierarchies.
- **Comment policy:** each loader-difference point comments the difference
  absorbed; each Mixin names the SPI method it backs.
- **Forbidden:** version-string parsing (probes only, §5.12); loader/MC types
  in `vine-api`/`vine-spi` signatures; Mixins outside the two driver projects
  or a second config per driver; global vanilla behavior changes.
- **Done means:** stages ticked, M0 gate green on both cells in CI, status →
  `done`, dashboard row updated in `docs/README.md`.
