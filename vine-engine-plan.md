# VINE — *Voxel Is Not an Engine*

**Universal Minecraft version-abstraction engine — master plan**

> GNU = "GNU's Not Unix". VINE = "Voxel Is Not an Engine": a library that wraps
> Minecraft so completely that Minecraft *becomes* the engine, while the library
> itself insists it is not one.
>
> **Minecraft is the engine. VINE is the engine SDK. Your mods are the games.**

Status: planning (rev 2026-09-23 #2: platform/plugin surfaces, datapack-registry
descriptor riding, party primitive; split into stage-granular subsystem plans)
Decisions locked: name (VINE), distribution (per-version jars, one source),
target matrix (4 driver cells + 26.x drop policy, see §3), locale model
(§5.18), client surface designed day-zero / implemented in phases (§5.11),
backend-partner policy (§5.20), authoring model (§5.2), posture
(source-available, personal pace, §11).
Execution docs: `docs/README.md` → `docs/subsystems/sub-*.md` — 22 agent-facing
sub-plans, one per subsystem, staged for parallel construction.

---

## 1. Vision

### 1.1 The problem

Porting a large mod between Minecraft versions is a full rewrite. The
ecosystem has accepted this as inevitable: every major MC version forks the
mod community (1.7.10, 1.12.2, 1.16.5, 1.20.1, 1.21.x are all living islands).

The cause is structural: **mod code compiles directly against Minecraft's
internal classes** (`net.minecraft.*`) and against one loader's API. Both are
moving targets. Mojang now ships multiple "game drops" per year, and starting
in 2026 versions are numbered `<year>.<drop>`. Confirmed in practice: three
drops in the first seven months of 2026 — 26.1 "Tiny Takeover", 26.2 "Chaos
Cubed", 26.3 "Wilderness Bound" (Sep 15). Version churn is permanent,
accelerating, and now observable rather than predicted.

*(Verified 2026-09-25 against minecraft.net's drop announcements: 26.1 shipped
2026-03-24, 26.2 on 2026-06-16, 26.3 on 2026-09-15, and 26.4 is already in
snapshots — Snapshot 1 landed 2026-09-22, i.e. the next drop is in flight while
the current one is four weeks old. That is the cadence the driver-cells model
exists for: a drop bump is a catalog edit plus a driver wave, never a consumer
change.)*

### 1.2 The thesis

Insert one thick, stable layer between mod code and the game:

```
 mod code  ──compiles against──▶  VINE API  (stable, version-free)
                                     │
                                VINE SPI  (internal driver contract)
                                     │
                ┌────────────┬───────┴───────┬────────────┐
           driver 1.21.1  driver 1.21.1  driver 26.x   driver 26.x
            (NeoForge)     (Fabric)      (NeoForge)    (Fabric)
                                     │
                              Minecraft + loader
```

A mod written against VINE is written **once**. Supporting a new Minecraft
version means writing a new **driver** inside VINE — the mod's source does not
change. The 26.x era is the thesis test: new version ⇒ new driver, zero
consumer-mod changes.

### 1.3 Positioning vs. the voxel-engine world

- **Luanti** (ex-Minetest) is the living proof of the end-state: an engine
  whose "games" are just mod collections over one stable content API (3100+
  mods on ContentDB). The Prime Invariant, independently discovered. VINE's
  inverse move: instead of rebuilding Minecraft on an engine, **wrap Minecraft
  until it is one**.
- **Bukkit/Spigot** proved the wrapper model: plugins compiled against the
  API, never touching NMS, routinely survive years of MC updates. VINE brings
  that discipline to the *modded* (client+server, Mixin-based) world.
- **Sponge** is the spiritual ancestor — but its API lines still track MC
  versions (API 8 ≈ 1.16, API 9 ≈ 1.18…). VINE's difference: the engine owns
  identity, data, and registration (§5), so one API line spans versions.

### 1.4 What "engine" means here (engine-architecture framing)

Per the canonical engine layering (Gregory, *Game Engine Architecture*):

| Engine layer | Owner in VINE's world |
|---|---|
| Hardware/OS abstraction, renderer, physics, protocol, world simulation | **Minecraft itself — never replaced** |
| Core systems, resource/content pipeline, **runtime gameplay foundations**, SDK tooling | **VINE** (`vine-api` / `vine-client-api` / `vine-core` / drivers) |
| The game: rules, content, sessions | **consumer mods** |

VINE is deliberately **not** a true engine. Honest non-goals: VINE never owns
the renderer, the main loop, physics, or the network protocol. That is what
keeps the name accurate — and what keeps the maintenance surface bounded:
every layer Mojang keeps moving (rendering, protocol) stays *below* VINE;
every layer mods actually write against (entities, data, rules, content)
moves *into* VINE.

Two engine-design rules adopted from the survey:

- **The Minimal Footprint Rule**: VINE + zero consumers = vanilla-identical.
  No global patches, no ambient gameplay modification. Every behavior change
  is a consumer-scoped opt-in. (The anti-pattern is the invasive overhaul mod
  that patches everything it touches; Mixin Quarantine, §5.9, is the
  mechanical half of this rule.)
- **The Gameplay Foundation Duty**: an engine SDK owes its games a
  session/rules layer, not just wrappers. VINE provides `VineSession`
  (§5.19), the GameMode/GameState pattern ported to Minecraft.

---

## 2. Naming

**Chosen: VINE — "Voxel Is Not an Engine"**

Recursive, GNU-style self-denial, and the joke is precise: the thing that turns
Minecraft into an engine claims not to be one. "Vine" also fits semantically —
it grows across versions and binds them.

| Candidate | Backronym | Verdict |
|---|---|---|
| **VINE** | Voxel Is Not an Engine | ✅ chosen |
| VOXEL | Voxel-Oriented Cross-version Engine Library | rejected — ungoogleable; collides with the generic term, VoxelCore (C++ engine), VoxelMap, VoxelSniper |
| VANE | Voxel Abstraction, Not an Engine | rejected — good second choice |
| VISE | Voxel Is a Standard Engine | rejected — positive claim, weaker joke |
| VICE | Voxel Integration & Compatibility Engine | rejected — VICE C64 emulator, negative connotation |

Collision scan for **VINE**: Vineflower (the decompiler) is the only notable
adjacency — same ecosystem, different domain (build toolchain vs. runtime
library); acceptable. Assorted "vine" decor mods are irrelevant (different
namespace).

Suggested coordinates (not locked):
- group: `dev.vineengine` · artifacts: `vine-api`, `vine-client-api`, `vine-core`, `vine-driver-*`
- Java root package: `dev.vineengine.vine` (public), `dev.vineengine.vine.internal` (SPI/drivers)
- Maven badges, docs site, and logo can lean into the vine/trellis motif.

---

## 3. Locked decisions

| Decision | Resolution | Rationale |
|---|---|---|
| Name | **VINE** | §2 |
| Distribution | **Per-version jars, one source** | Consumer source is version-free; Gradle stamps `mymod-1.21.1.jar`, `mymod-26.1.jar`, … from one tree. JourneyMap/Xaero/ValkyrienSkies/ReplayMod precedent. No runtime classloading magic, no fat-jar Mixin hazards. |
| Target matrix | **1.21.1 NeoForge · 1.21.1 Fabric · 26.x NeoForge · 26.x Fabric** (4 cells) | 1.21.1 = prototype springboard: mature toolchains, Data Components era, covers both mappings worlds (Mojmap + intermediary). 26.x = the new era: unobfuscated runtime, Java 25, Vulkan transition. Version axis proven by the 1.21.1→26.x jump (renumbering + semantic breaks); loader axis proven by NF/Fabric on both versions. |
| 26.x drop policy | **Latest drop + previous drop** | At ~3 drops/year, two concurrent 26.x cells bound maintenance; consumers get one grace drop to rebuild. Cells retire by policy, never by decay. |
| 1.21.1 lifetime | **Kept long-term** | Large existing modpack audience; stays a permanent cell even after the 26.x pair passes the TCK. |
| Consumer release matrix | **Per-mod subset of the 4 cells** | The engine supports the matrix; each consumer ships whichever cells it chooses. |
| Locale model | **Seamless overworld + fixed special-activity dimensions** | Hunts/activities live in the overworld; special activities may ship fixed dimensions. Per-party instanced maps are explicitly out of scope (§5.18). |
| Client surface | **Designed day-zero, implemented in phases** | The *design* covers the full client surface now (2D + 3D + animation) so no later phase invalidates core decisions; *implementation* is phased (2D in M2–M3, 3D last — Mojang is mid-Vulkan-migration). §5.11. |
| Backend-partner policy | **Partner formats adopted; partners hard-required where used** | VINE adopts GeckoLib's model/animation asset format as its own interchange format; GeckoLib is the render backend (hard dependency) on cells where it ships. No obligation to write a VINE-native fallback renderer. Partner drift is quarantined inside drivers (§5.20). |
| Authoring model | **Java + JSON descriptors; no scripting layer** | Registration descriptors are pure data — declarable from code or datapack-style JSON (hot-reloadable). Lua/JS scripting is explicitly out of scope (can layer on descriptors later without breaking them). |
| Posture | **Source-available, personal pace** | Public repo, Apache-2.0 intent for `vine-api`/`vine-client-api`, no contribution/governance process until a second consumer exists. §11. |
| Explicitly out | **Everything below 1.21.1** (incl. 1.7.10, 1.12.2, 1.16.5) | Legacy constraints (integer IDs, 4-bit metadata, pre-Brigadier, Java 8) dominated v1 of this plan; their driver cost is disproportionate now that the era target is 26.x. Old versions remain read-only reference material. |

---

## 4. Precedents & landscape

| Project | Axis it solves | What VINE takes from it |
|---|---|---|
| **Bukkit/Spigot API** | version (server) | The Prime Invariant: plugins never touch NMS ⇒ binaries survive updates. Proof the wrapper model works at ecosystem scale. |
| **Sponge (SpongeAPI)** | version+platform (server) | API/implementation split, service-oriented design. Also the cautionary lesson: if the API leaks version-shaped concepts, API lines re-fork per MC version. |
| **Luanti** | the whole end-state | Engine + games-as-mod-collections over one stable Lua API; ContentDB ecosystem. The voxel proof of VINE's thesis, inverted (§1.3). |
| **Game Engine Architecture** (Gregory) / **Unreal Gameplay Framework** | engine layering, session model | The layer table (§1.4) and the GameMode/GameState server-authority pattern (§5.19). |
| **Architectury** | loader (not version) | Common-module structure; but it abstracts Forge/Fabric, *not* MC versions. VINE is orthogonal and deeper. |
| **Stonecutter** (kikugie) | version, build-time | `//? if >=1.21.2` comment preprocessing, one subproject per target. Candidate tooling *inside drivers*; consumers should never need it (their code has no version conditionals). |
| **ReplayMod preprocessor** | version, build-time | `//#if MC>=…`, per-version file overrides, `@Pattern` remapping. Proof that one-source/many-version is maintainable for years. |
| **Sinytra Connector** | loader, runtime | Runtime translation of Fabric mods onto NeoForge — the opposite direction (adapt foreign mods at runtime). Its Mixin-fragility lessons inform VINE's Mixin Quarantine rule (§5.9). Alive on 26.1.2. |
| **ViaVersion** | protocol | Version-agnostic core + per-version protocol adapters at scale; same adapter philosophy, different layer. |
| **JourneyMap / Xaero's / ValkyrienSkies** | distribution | Big multi-version mods ship per-version jars from one source — validates the locked distribution model. |
| **GeckoLib** | animation/model format + render backend | The canonical Blockbench-era asset format (geo/animation JSON) and its per-cell render runtime. VINE adopts the format and the backend (§5.14, §5.20); its generational churn (GL4→GL5) is quarantined inside drivers. |
| **Luanti / VoxeLibre, VoxelCore** | — | Naming/positioning context; the "engine" framing VINE inverts. |

Key insight from the survey: **nobody currently owns the version axis for
mods.** Loaders are axis 1 (mostly settled per version), Stonecutter/ReplayMod
are build tools (they help *you* manage drift in *your* code), Connector is
runtime loader-translation. A maintained, version-spanning *content API* for
mods is open territory.

---

## 5. System design

### 5.0 Modules

```
vine/
├─ vine-api/            ← consumers compile against ONLY this. Zero net.minecraft/loader types.
├─ vine-client-api/     ← client surfaces: GUI/HUD, input, model/animation descriptors, audio. Same Prime Invariant.
├─ vine-core/           ← version-free engine logic (ID maps, codecs, bus, data model, VineBrain, animation evaluator, VineSession)
├─ vine-spi/            ← driver contract (internal; may live inside vine-core)
├─ drivers/
│  ├─ driver-1.21.1-neoforge/
│  ├─ driver-1.21.1-fabric/
│  ├─ driver-26.x-neoforge/      (latest + previous drop per §3)
│  └─ driver-26.x-fabric/
├─ vine-tck/            ← technology compatibility kit (§7)
└─ vine-testmod/        ← consumer-style mod exercising every API surface
```

**Bytecode floor:** all shared modules (`vine-api`, `vine-client-api`,
`vine-core`, `vine-spi`) compile to **Java 21 bytecode** — the 1.21.1 cell sets
the floor. Records/sealed/pattern matching are allowed in shared code; 26.x
drivers may use Java 25 features internally.

### 5.1 The Prime Invariant & the Minimal Footprint Rule

> **Prime Invariant — no `net.minecraft.*`, no loader class, and no Mixin
> appears in `vine-api`'s public signatures, and consumers MUST NOT reference
> them.**

This is the single rule that makes everything else possible (Bukkit's entire
longevity comes from it). Consequences:

- Consumer source is **version-free by construction** — there is nothing
  version-shaped left to reference. No preprocessor needed in consumer code.
- Per-version builds are plain Gradle multi-project builds with dependency
  substitution (`mymod-26.1` links `vine-api` + `driver-26.x-*`), not a
  preprocessing pipeline.
- Escape hatch: `@VineUnsafe` bridge giving raw access to the native object
  graph — allowed, but annotated, grep-able, and explicitly voids portability
  for that code unit. Released mods may ship `@VineUnsafe` usage with a
  manifest flag so tooling can warn. It also defines the **donation path**:
  consumers prototype un-covered surfaces (e.g. custom 3D rendering) per-version
  behind the hatch, and those prototypes become the design input when VINE
  absorbs the surface officially (§5.11, §11).

> **Minimal Footprint Rule — VINE with zero consumers installed is
> behaviorally identical to vanilla.** All hooks are dormant until a consumer
> registers content or subscribes. VINE never globally rewrites vanilla
> behavior on its own; every modification is consumer-scoped and opt-in. This
> is what makes VINE coexist with arbitrary non-VINE mods (§5.20) instead of
> becoming an invasive coremod.

One allowed external type in API signatures: `com.mojang.serialization`
(DataFixerUpper) — a standalone, version-stable Mojang library, *not*
`net.minecraft`; descriptor `Codec`s ride it. If Mojang ever breaks it, it gets
shaded+forked and the API still doesn't change.

### 5.2 Identity & registries

The remaining fault line: **1.21.1** = Mojang `Registry`, data-driven
registries, `ResourceKey`, loader-specific registration phases; **26.x** =
further data-driving, unobfuscated runtime.

VINE design:

- Mods declare **registration descriptors** (pure data: `vine:block`,
  `vine:item`, `vine:entity`, `vine:recipe`, properties, behaviors) — **from
  Java code or from datapack-style JSON** (locked: Java + JSON authoring; no
  scripting layer). JSON descriptors are hot-reloadable where the host
  version's reload system allows.
- The engine assigns **stable VINE IDs** and keeps its own persistent ID map
  (the Forge registry-persistence idea, but engine-owned, so it works
  identically on Fabric).
- Each driver materializes natives at the correct per-version phase
  (`DeferredRegister`/data-pack hooks; per-loader wiring differs, consumer
  never sees it).
- Dimensions are namespaced in VINE everywhere.
- **Two descriptor classes** (locked 2026-09-23): *structural* descriptors
  (blocks, items, entities, sounds, recipe types) materialize into static
  startup registries; *design* descriptors (abilities, quests, monster
  definitions, skill nodes, loot modifiers) ride **vanilla dynamic datapack
  registries** — drivers emit `DataPackRegistryEvent.NewRegistry` (NeoForge) /
  `DynamicRegistries.registerSynced` (Fabric) with the descriptor's `Codec`,
  gaining datapack override, `/reload`, and server→client sync for free. JSON
  layout follows vanilla convention
  (`data/<entry-ns>/<registry-ns>/<registry-path>/<entry>.json`) so pack makers
  use familiar tooling, and datapacks can override VINE content without code.

**The VINE Content Pipeline** (engine-pattern: source assets vs. cooked
assets): consumers ship source assets (textures, GeckoLib-format
models/animations, sounds, descriptors); each per-cell build/datagen run cooks
the version-correct runtime assets (blockstate JSON, item-model definitions,
`sounds.json` formats). Datagen is a first-class pipeline stage owned by the
drivers, not a consumer chore.

### 5.3 Blocks, blockstates, items

- **Flattened model natively**: every supported cell is post-1.13; namespaced
  blocks and property-based states are the only model.
- **Items**: durability is an engine-managed data key (§5.4), not item
  identity.
- Content is **behavior composition, not inheritance**: a VINE block/item is a
  definition + behavior interfaces (`onUse`, `tick`, `loot`, `model` hints…).
  Drivers construct the native singleton delegates. Class hierarchies never
  cross the API boundary (they are exactly what breaks between versions).
  (Engine-pattern note: composition over inheritance is the settled entity
  model; a full data-oriented ECS is rejected — its payoff is memory-layout
  performance, and Minecraft owns entity storage and ticking.)

### 5.4 Data persistence — NBT vs Data Components

Every supported cell is post-1.20.5, so Data Components are universal — the
codec story is uniform:

- *Portable strategy (default)*: VINE data lives in an engine-owned Data
  Component (`VoxelData`, a self-describing NBT-like tree) on every cell.
  Guaranteed identical semantics; save files round-trip through the TCK's
  golden fixtures.
- *Native strategy (opt-in per field)*: map to the version's native components
  for interop with vanilla or other mods.
- Schemas are versioned by the engine (not by MC), with engine-side datafixers
  — decoupling mod-data migration from Mojang's DataFixerUpper versions.
  Mojang's drop cadence (three data-version bumps in seven months of 2026)
  exercises this layer for free.

### 5.5 Capabilities

Energy/fluid/inventory/custom — one engine capability registry:

- NeoForge cells: the modern (1.20.5+) capability system.
- Fabric cells: Fabric API lookups where available; otherwise the engine's own
  fallback store — which is also the **portability guarantee floor**: a
  capability always works, interop is best-effort per driver.
- Interop bridges (FE/RF, common fluid tags) are explicit driver modules
  (§5.20), not afterthoughts.

### 5.6 Networking

- Engine **channels + a codec DSL** over a VINE `ByteBuf` facade; consumers
  never see `FriendlyByteBuf`/`StreamCodec`.
- Drivers map to vanilla custom payloads (NeoForge registration / Fabric
  payload API) — uniform across the matrix.
- Payload versioning is engine-managed; handshake negotiates engine protocol,
  orthogonal to MC protocol (ViaVersion territory stays untouched).

### 5.7 Commands

Brigadier is native on every supported cell. The VINE command DSL is a thin,
declarative layer over Brigadier (registration descriptors, engine-side
permission/completion hooks); drivers do near-mechanical translation. No
legacy gap exists in the matrix.

### 5.8 Events & lifecycle

- Engine event bus with normalized lifecycle phases (`VINE_BOOT`,
  `REGISTRIES_FROZEN`, `WORLD_LOAD`, …) independent of loader phases.
- Drivers translate: NeoForge bus events, Fabric callback registries.
- Consumer handlers subscribe to engine events only.
- **Normalized hook families** (the commonly-used, version-volatile APIs this
  layer exists to absorb): entity (spawn, tick, damage pre/post, death,
  interact, loot, target change), item (use, tick, craft, repair, durability
  change), player (login, logout, clone/respawn, dimension change), block
  (place, break, neighbor change), world (load, unload, tick, weather change).
  Each hook is an engine event with defined ordering guarantees; each driver
  translates its loader's events once.

### 5.9 Mixin Quarantine

All Mixins live inside drivers, one Mixin config per driver, refmaps where the
cell still has mappings. Consumers use engine hooks. Rationale (Connector's
experience): cross-environment Mixin application is the #1 fragility source;
centralizing it makes failures owned and fixable in one place. On 26.x cells
the unobfuscated runtime reduces (not eliminates) the need for Mixins —
prefer accessor/interface patterns where Mojang's code allows.

### 5.10 Mappings & toolchains per cell (drivers only; invisible to consumers)

| Cell | Dev mappings | Runtime names | Toolchain |
|---|---|---|---|
| 1.21.1 NeoForge | Mojmap (+Parchment) | Mojmap | NeoGradle or ModDevGradle, Java 21 |
| 1.21.1 Fabric | Yarn/intermediary | intermediary | fabric-loom (remapping), Java 21 |
| 26.x NeoForge | **Mojmap only** (game ships unobfuscated) | none — names ship | ModDevGradle, **Java 25** |
| 26.x Fabric | **Mojmap only** (Yarn discontinued for 26.1+) | none — names ship | fabric-loom (non-remapping), **Java 25** |

**26.1 changed the mappings axis permanently**: Mojang ships unobfuscated
binaries, so dev names = runtime names, reflection and stack traces just work,
and no remapping step exists. The mappings problem now belongs entirely to the
1.21.1 cells — where it was already solved. CI runs a JDK matrix (**21 / 25**)
alongside the version matrix.

*(Verified 2026-09-25 against Fabric's own porting docs,
github.com/FabricMC/fabric-docs → versions/26.1.2/develop/porting/{index,mappings,loom}:
Yarn and Intermediary are unmaintained past 1.21.11, 26.1 needs no `mappings`
dependency because the game is already unobfuscated, and the plugin id itself
changes — `net.fabricmc.fabric-loom` for 26.1+, with `jar` replacing `remapJar`.
The repo's `build-logic/vine.driver-26.x-fabric.gradle` already applies that
plugin id and documents the same two consequences, so the provision and the
practice agree.)*

### 5.11 Client surface — designed day-zero, implemented in phases

**Locked 2026-09-23**: the plan covers the *entire* client surface from day
zero. Deferring design (not just implementation) would let later phases
invalidate earlier core decisions. Implementation is phased because the 3D
stack is the fastest-moving target in Minecraft's history: 26.2 shipped
experimental Vulkan with a Graphics API selector, and Vibrant Visuals for Java
is in development with no date. Design pins *concepts* (retained-mode
descriptions, capability probes), never GL/RenderSystem calls.

The client surface splits into two halves with opposite stability:

- **2D — screens/GUI toolkit, HUD overlays, keybinds, audio.** Cross-version
  stable (`Screen` delegate patterns are well understood). **Implemented in
  M2–M3.**
- **3D — entity model/animation rendering, particles/FX, camera, shaders.**
  Implemented last (M5), after Mojang's Vulkan migration settles. The
  animation *data model and server-side evaluator* are **not** deferred —
  they are combat-critical server logic and are designed with entities at M2
  (§5.14).

- **Materials are PBR-aware from day zero, probe-gated** (verified
  2026-09-23): descriptors always carry optional labPBR-convention maps (`_n` =
  normal+AO+height, `_s` = smoothness/F0/porosity/emissive); rendering uses
  them when a shader chain exists — the **Sodium + Iris** stack (locked,
  §5.20) or OptiFine today, the official Vibrant
  Visuals Java spec whenever Mojang publishes it (absorbed by the 26.x driver,
  no API change). GeckoLib's native ceiling is emissive glowmasks only, so
  guaranteed no-shader PBR is custom-renderer/`@VineUnsafe` territory until
  then (sub-17).

Until a surface is implemented, consumers get `VineEngine.supports(ClientFeature.X)`
capability queries (never version checks), and prototype per-version behind
`@VineUnsafe` — the donation path that feeds real usage back into the official
design (§5.1).

### 5.12 Version detection — data, not strings

Drivers select and self-check via `SharedConstants` **data version** and a
**feature probe matrix** (`hasDataComponents`, `dataDrivenRegistries`,
`unobfuscatedRuntime`, `vulkanRenderer`, …) — never by parsing
`"1.21.1"`-style strings. The 26.x renumbering (`<year>.<drop>`,
non-retroactive) is then a non-event, and the rule survives every future
scheme change. Unsupported runtime (e.g. a 26.x drop older/newer than the
latest+previous window) ⇒ clean, explicit boot failure listing supported
cells.

### 5.13 Entities & AI — engine-owned runtime, natively hosted

Same move as `VoxelData`: own the semantics, host on the native game. Mapping
VINE behavior to native AI would leak version semantics (goal selectors vs.
Brain hybrids) — forbidden by the Prime Invariant.

- **`VineBrain`**: an engine-owned behavior runtime (state machine / behavior
  tree hybrid) ticked by the engine. Identical semantics on every cell;
  determinism is TCK-tested via golden tick-traces.
- Drivers supply **primitives only**: pathfind requests, move/look control,
  line-of-sight and target queries, spawn/despawn. The scheduler never crosses
  the boundary.
- **Multipart entities**: large entities are a root entity + part descriptors
  — parent bone, size/offset, per-part damage-type multipliers
  (sever/blunt/shot-style typing), flinch/break thresholds, **per-part
  persistent state** (e.g. wounds). Part positions are server-authoritative
  (derived from the server-side animation evaluator, §5.14). Hosting:
  `PartEntity` (NeoForge formalizes; Fabric hosts the vanilla mechanism
  manually).
- **Riding/mounts**: engine ride API with control-input sync.
- **Territory semantics**: leash ranges, area-transition intents, and
  herd/pack grouping (alpha + members) are first-class in `VineBrain` —
  required by the seamless-overworld locale model (§5.18).

### 5.14 Animation runtime — GeckoLib format, server-evaluated, driver-rendered

**Locked 2026-09-23**: VINE adopts the **GeckoLib asset format** (Blockbench
geo/animation JSON) as its model/animation interchange format — the tooling
ecosystem (Blockbench plugins, exporter pipelines) already exists, and
designing a competing format is needless weight.

- **One asset, two consumers**: the same GeckoLib-format asset feeds (a) the
  server-side evaluator and (b) the client render backend.
- **Server-side evaluator** (in `vine-core`, headless): parses animation data
  to compute attack windows (startup / active / recovery / combo-cancel) and
  bone-attached collider positions for multipart hitboxes (§5.13).
  Deterministic; TCK golden timing fixtures run on every cell without a
  client. Animation is *data driving combat timing*, not just visuals.
- **Client render backend**: GeckoLib (the mod) is the render backend and a
  **hard dependency on cells where it ships** (locked). No obligation to write
  a VINE-native fallback renderer; a future backend remains possible behind
  the same SPI. GeckoLib's generational churn (GL4 on 1.21.1, GL5 on 1.21.5+)
  is quarantined inside the per-cell drivers — consumers only ever see VINE
  animation IDs. The GL4→GL5 break includes a package namespace move
  (`software.bernie.geckolib` → `com.geckolib`, verified 2026-09-25 against
  wiki.geckolib.com/docs/geckolib5), so the two adapters probe different
  namespaces and the boundary is never inferred from a version string.
- Consumers register animation assets and play them by ID. Sync model: the
  server selects/approves the animation; clients render it.

### 5.15 Input & combat pipeline

- **Input/action API**: keybind/action registration; client→server action
  requests; server-side validation and deterministic ordering. Supports
  buffered inputs, combo windows, and cancels.
- **Combat is an ordered pipeline, not events**: attack descriptor (motion
  value, damage type, element) → part resolution (hitzone multipliers,
  equipment modifiers, part state — §5.13) → modifiers (skills/affinities) →
  application (i-frames respected, knockback override, hitstop). Each loader's
  hurt/attack events normalize into this pipeline once, in the driver. Per the
  Minimal Footprint Rule, the pipeline applies only to consumer-registered
  content — vanilla combat is untouched unless a consumer opts in per
  entity/item.
- **I-frames and hitstop are engine-owned and server-authoritative**; the
  client presents them (flash, shake, freeze-frames) via the client surface.

### 5.16 Recipes & crafting

- Descriptor registration, identical to blocks/items: declare (Java or JSON) →
  engine IDs → driver materializes per cell (data-driven recipe types).
- **Custom recipe types are first-class** (non-crafting-grid systems:
  machines, rituals, upgrade paths).
- Recipe-viewer interop (JEI/REI/EMI per cell) ships as explicit driver
  bridge modules (§5.20).

### 5.17 Audio

- Engine sound events registered as descriptors; drivers generate the
  per-version `sounds.json` formats via the content pipeline (§5.2).
- Positional playback with attenuation, entity-attached sounds, and **dynamic
  music channels** (area ↔ activity theme switching hooks).

### 5.18 World population & locales

**Locked 2026-09-23**: activities live in the **seamless overworld** as the
primary mode; special activities may ship **fixed locale dimensions** (covered
by §5.2 descriptors). Per-party instanced maps are **explicitly out of
scope** — region copy + per-party entity isolation is the hardest possible
surface; revisit only on concrete demand.

Engine surfaces required by seamless activity design:

- **Spawn replacement**: suppress/redirect vanilla spawns per biome/region;
  engine spawn rules registered as data. Consumer-scoped (Minimal Footprint).
- **Population control**: density caps, territory assignment, despawn rules —
  the guardrail that makes multiple large multipart entities + herds
  survivable at tick time.
- **World clock/weather events**: engine-normalized; custom weather *states*
  are consumer world data + client presentation.
- **Teleport & waypoint primitives**: safe cross-position/cross-dimension
  teleport with cost/cooldown hooks; engine-persisted waypoint registry
  (`VoxelData`); respawn-point override event with explicit-binding semantics —
  activation ≠ respawn change, binding is a separate deliberate act (Waystones
  lesson). Camp/fast-travel-class systems are consumer content composed from
  these primitives.
- TCK gains a **performance scenario**: N large multipart entities + herds in
  an overworld; per-cell tick budget enforced.

### 5.19 Gameplay session framework — `VineSession`

The GameMode/GameState pattern (Unreal gameplay framework) ported to
Minecraft, built over `VoxelData` (§5.4) + networking (§5.6) + lifecycle
(§5.8). This is the "gameplay foundation" an engine SDK owes its games — the
layer that turns "a world with content" into "a game with rules".

- **Rules object** (server-only): owns session lifecycle (created → active →
  completed/abandoned), validates actions, decides outcomes. Never replicated;
  clients request, server decides.
- **Session state** (replicated): public, session-scoped state (phase, timer,
  objectives, shared flags) synced to participating clients.
- **Per-player state** (replicated to owner; optionally public): participant
  contribution, loadout, progress.
- Scoping: per-world or per-party; persistence through `VoxelData`; players
  can join/leave mid-session (late-join sync is engine-owned).
- **Party primitive**: engine-owned party/team store (`VoxelData`-persisted) —
  members share session/quest progress, ranks, allies (no shared progress),
  API-only creation mode, join/leave events (FTB Teams pattern, engine-owned so
  it works identically with nothing else installed).
- Consumers compose sessions for any structured activity — hunts, dungeon
  runs, arena matches, quests — without re-solving authority/replication each
  time.

### 5.20 Ecosystem cooperation — three directions

VINE is a platform: it must cooperate with the mod ecosystem in both
directions and coexist with everything else.

1. **Backend partners (VINE consumes ecosystem mods).** Optional per-driver
   modules that sit *below* the SPI. Named partners:
   - **GeckoLib** — model/animation render backend. Locked policy: VINE adopts
     the GeckoLib **asset format** as its interchange format, and GeckoLib is
     a **hard dependency** on cells where it ships (§5.14). No VINE-native
     fallback renderer is owed.
   - **JEI/REI/EMI** — recipe-viewer bridges per cell.
   - **Curios/Trinkets/Accessories** — accessory-slot bridges: Curios
     (NeoForge), Trinkets (Fabric legacy), Accessories (cross-loader,
     data-driven); engine fallback store is the portability floor (§5.5).
   - **Loader energy/fluid APIs** — interop bridges (§5.5).
   - **Sodium + Iris** — the render-performance & shader chain (locked primary
     stack, verified 2026-09-23): both officially multi-loader (NeoForge
     1.21.1 + 26.x; Fabric). Iris-on-NeoForge *requires* Sodium and declares
     Embeddium incompatible (frozen since Jan 2025, no 26.x port); Oculus is
     legacy (≤1.20.1), superseded by official multi-loader Iris. Probe-only
     partner: presence enables `PBR_MATERIALS`-class features; absence never
     breaks.
   Rules: the consumer-facing API never names a partner; partner *version*
   drift (e.g. GL4→GL5) is quarantined inside the driver that uses it; except
   for locked hard dependencies (GeckoLib), partner absence degrades
   capability via feature probe — it never breaks a consumer. Bridge modules
   are **soft-dependency isolated**: presence-probed at boot, integration
   classes never classloaded when the partner is absent. Activation ordering:
   NeoForge via loader metadata (`after:`/`before:`); Fabric has **no**
   cross-mod ordering metadata (`depends`/`breaks` are presence constraints,
   not ordering — verified 2026-09-23), so Fabric bridges use deferred
   activation (custom entrypoints / `FabricLoader#getEntrypointContainers`).
2. **Downstream consumers & extension points (mods consume and extend VINE).**
   The per-version-jar model (§6). Consumers also *extend* the engine without
   touching it: engine-owned extension-point registries accept custom behavior
   node types (§5.13), capability types (§5.5), descriptor types (§5.2),
   combat pipeline stages (§5.15), quest objective/reward types (§5.21), and
   session types (§5.19). Inter-consumer interop rides VINE IDs: consumer B
   references consumer A's content in recipes/capabilities/sessions with no
   direct dependency between them. Two exemplar consumers with disjoint demand
   profiles keep the API honest (§10.1).
3. **Coexistence (VINE beside non-VINE mods).** Guaranteed by the Minimal
   Footprint Rule (§5.1) + Mixin Quarantine (§5.9): VINE changes nothing
   unless a consumer opts in; foreign blocks/entities/data interop proceeds
   through native-strategy data (§5.4) and bridge modules — never through
   global patches.

### 5.21 Quests & activities — data-driven, party-aware

Validated by the FTB Quests/FTB Teams precedent (SNBT quest books, objective/
reward type registries, team-shared progress): a quest system is platform-level
surface, not consumer content.

- **Quest descriptors are design descriptors** (§5.2): chapters/quests/
  objectives/rewards in JSON, datapack-reloadable, server→client synced.
- **Objective & reward types are extension points** (§5.20): engine ships
  kill/collect/reach/deliver/interact/custom-event objectives; consumers
  register new types without touching engine code.
- **Progress is party-aware** via the party primitive (§5.19): members share
  progress, allies don't.
- **Presentation**: engine owns data + tracking + a default 2D-toolkit GUI
  (§5.11); consumers may reskin.
- Quest boards, camps, fast-travel stations are consumer content composed from
  §5.18 primitives + blocks/items + the 2D toolkit — VINE provides the
  primitives, not the game design.

---

## 6. Consumer build model ("per-version jars, one source")

Consumer repo:

```
mymod/
├─ src/main/java/…        ← 100% pure VINE API; no //? conditionals, ever
├─ versions/
│  ├─ 1.21.1-neoforge/   (build.gradle: deps = vine-api + vine-driver-1.21.1-neoforge)
│  ├─ 1.21.1-fabric/
│  ├─ 26.x-neoforge/
│  └─ 26.x-fabric/       (subsets allowed — consumers pick their cells)
└─ build.gradle / settings.gradle (plain multi-project; no preprocessor plugin required)
```

Because consumer code carries no version conditionals, this is *simpler* than
a Stonecutter/ReplayMod setup: the per-version subprojects only re-link
dependencies and repackage resources. Stonecutter remains available **inside
VINE drivers** if driver-internal drift justifies it — decision deferred to
M1, plain shared-`vine-core` subprojects preferred (IDE-friendly, no source
generation).

---

## 7. Testing — the TCK

`vine-tck` is the compatibility kit every driver must pass; it *is* the
definition of "VINE works on version X".

- **Headless server boot** per cell in CI (GitHub Actions matrix: 4 cells ×
  JDK 21/25), running `vine-testmod` scenarios:
  registration (Java + JSON descriptor paths) → world place/break →
  persistence round-trip across save/load → packet echo → command execution →
  capability store/retrieve → recipe materialization → multipart damage
  routing + per-part state persistence → `VineBrain` golden tick-trace
  determinism → animation server-evaluator timing fixtures → spawn-control
  scenario → audio event smoke → `VineSession` lifecycle + late-join sync.
- **Golden fixtures**: pinned world saves + `VoxelData` binaries; a save
  written by the 1.21.1 driver must read back identically on 26.x
  (engine-level datafix path), and vice versa.
- GameTest harness on all cells (1.21.1+ provides it).
- **Performance scenario**: N large multipart entities + herds in an
  overworld; per-cell tick budget (§5.18).
- **Client smoke**: manual/scripted checklist per cell until the 2D toolkit
  (M2–M3) and 3D phase (M5) create automatable surfaces.
- Rule: an API surface ships only when its TCK scenario passes on **all**
  supported cells. This prevents silent lowest-common-denominator erosion in
  both directions.

---

## 8. Versioning & governance

- `vine-api` is SemVer. Breaking API change ⇒ major bump; drivers are released
  per API major.
- Stability tiers via annotation: `@Stable` (SemVer-guaranteed),
  `@Incubating` (may break in minor), `@Internal` (no guarantee), `@VineUnsafe`
  (portability-voiding escape hatch, §5.1).
- Deprecation policy: ≥ one major cycle with migration notes.
- Feature negotiation: `VineEngine.supports(...)` capability queries — the
  supported answer to "version X can't do this" (avoids the
  lowest-common-denominator trap while keeping core guarantees truly common).
- Contribution rule: new surface = API + TCK scenario + implementation on ≥2
  drivers (one per loader family), or it doesn't merge.
- Two known exemplar consumers with **disjoint** demand profiles (one
  entity/combat/client-heavy, one systems/persistence/GUI-heavy) keep this
  rule honest — an API shaped by one consumer is always secretly that
  consumer.

---

## 9. Risk register

| Risk | Severity | Mitigation |
|---|---|---|
| Mojang drop cadence (confirmed: 3 drops in 7 months of 2026) | high | Drop policy locked (latest + previous drop, §3). Drivers are the *only* versioned code; the cadence is exactly what the design amortizes. |
| Renderer replacement mid-flight (experimental Vulkan in 26.2; Vibrant Visuals undated) | high | 3D implementation phased last; design pins retained-mode *concepts* + capability probes, never GL/RenderSystem calls (§5.11). |
| Mojang semantic breaks (Data Components precedent; future per-drop breaks) | high | Engine owns the data model (§5.4); feature probes isolate change (§5.12). |
| Animation server-side evaluator is novel and hard (headless bone/collider math, attack windows) | medium | GeckoLib format is well-documented and parseable; evaluator is pure data math (no rendering); TCK golden timing fixtures pin correctness (§5.14). |
| Backend-partner drift (GeckoLib GL4→GL5 churn; partner abandonment) | medium | Partners live below the SPI inside drivers (§5.20); consumers see only VINE IDs; hard-dep policy accepted for GeckoLib — abandonment would trigger a VINE-native backend via the same SPI, not an API change. |
| Seamless-overworld performance (multiple large multipart entities + herds) | medium | Population control / territory caps are engine surfaces (§5.18); TCK performance scenario with per-cell tick budgets. |
| `VoxelData` performance overhead on hot paths (tick, worldgen) | medium | Zero-copy views where possible; `@FastPath` raw hooks for proven hotspots; benchmark in TCK. |
| Mixin fragility across cells | medium | Mixin Quarantine (§5.9); per-driver configs; TCK boots every cell; 26.x unobfuscation reduces Mixin need. |
| Loader politics (Forge/NeoForge split history repeating) | medium | Loader is just another driver axis — proven by shipping Fabric + NeoForge in the matrix. |
| Scope creep from consumers pulling the API sideways | medium | API grows only via the §8 contribution rule; dogfood subsystems, not whole mods, until M3. |
| Ecosystem interop (foreign energy/fluid/recipes) | medium | Explicit driver-side bridge modules; native-strategy data mapping (§5.4). |
| "Second system" over-abstraction | medium | M0 is a spike with hard acceptance criteria; kill/redirect before M1 if the two-driver spike isn't clean. |
| Mappings/licensing drift | low | 26.x collapsed this axis (unobfuscated runtime, Mojmap-only); 1.21.1 cells keep their solved Mojmap/intermediary story; mappings never enter `vine-api`. |

---

## 10. Milestones

- **M0 — spike (acceptance gate).** `vine-api` skeleton + **two drivers on one
  version: 1.21.1-NeoForge and 1.21.1-Fabric** (same version isolates the
  loader axis; both toolchains are mature). One block, one item, one engine
  event, one packet, one command; TCK boots dedicated servers on both. *Pass =
  identical testmod source compiles and runs on both loaders.* The version-axis
  gate is M4. Fail ⇒ reassess before investing further.
- **M1 — core depth.** `VoxelData` persistence + capabilities + engine config +
  `VineSession` basics; golden-fixture round-trip tests. Dogfood: one real
  subsystem from the first exemplar consumer's working 1.21.1 reference.
- **M2 — entities & combat core.** Entities + `VineBrain` AI runtime +
  multipart hitboxes (§5.13) + animation data model & server evaluator
  (§5.14) + input/combat pipeline (§5.15); 2D client toolkit begins
  (screens/GUI, HUD, input, audio — §5.11).
- **M3 — breadth.** Worldgen basics + loot + recipes (§5.16) + audio (§5.17) +
  spawn/population control (§5.18); 2D client toolkit completes; `VineSession`
  completes (late-join, persistence).
- **M4 — thesis validation (the version axis).** 26.x NeoForge + Fabric
  drivers on the then-current drop window. *Pass = consumer mods rebuild for
  26.x with zero source changes.* Toolchain is ready today: ModDevGradle /
  non-remapping loom, Java 25, no mappings step.
- **M5 — client 3D implementation.** Retained-mode models, GeckoLib render
  backend per cell, particles/FX, camera. Design input: consumer prototypes
  donated through `@VineUnsafe` (§5.1, §5.11).

### 10.1 Consumer tracks (demand exemplars, not content plans)

- **Consumer A — entity/combat/client-heavy** (modern cells). Drives
  §5.13–§5.15 (entities, animation, combat), §5.18 (population control), and
  the 3D client phase; prototypes 3D per-version behind `@VineUnsafe` and
  donates back into M5.
- **Consumer B — systems/persistence/GUI-heavy**. A large legacy-era RPG mod
  whose direct 1.21.1 port proceeds *outside* VINE first as the API design
  reference; full migration onto VINE follows, driving §5.4–§5.6, §5.16, and
  the 2D GUI toolkit. Its arrival as a second live consumer triggers the
  governance question (§11).

### 10.2 Subsystem mapping (docs/subsystems/)

| Milestone | Subsystems |
|---|---|
| M0 | sub-00 repository · sub-01 core-runtime · sub-02 registry (minimal) · sub-05 networking (minimal) · sub-18 driver-1.21.1 · sub-21 tck · sub-22 testmod |
| M1 | sub-03 voxeldata · sub-04 capabilities · sub-06 commands · sub-14 sessions (basics) |
| M2 | sub-08 entities/VineBrain · sub-09 animation (data+evaluator) · sub-10 combat/input · sub-16 client-2d (begins) |
| M3 | sub-07 blocks/items (full) · sub-11 recipes · sub-12 audio · sub-13 world · sub-14 sessions (full) · sub-15 quests · sub-16 client-2d (completes) |
| M4 | sub-19 driver-26x |
| M5 | sub-09 (render polish) · sub-17 client-3d |
| Continuous | sub-20 ecosystem-bridges · sub-21 tck · sub-22 testmod |

---

## 11. Open questions

1. License/posture — **resolved** (2026-09-23): source-available, personal
   pace; Apache-2.0 intent for `vine-api`/`vine-client-api`; drivers same until
   a split matters. Publishing: **local maven repo until M2** (locked), GitHub
   Packages at M2, Maven Central revisited at first external consumer.
2. ~~`@VineUnsafe` policy for released mods~~ → **locked** (2026-09-23):
   allowed; consumer jars declare the `Vine-Unsafe: true` manifest flag; boot
   bytecode scan + tooling warn on mismatch. Includes the **donation path**:
   consumer prototypes feed official surface designs (§5.1, §5.11).
3. Interop bridge priority: FE/RF energy vs. item/fluid handler bridging first
   — decided by the first consumer migration's actual needs.
4. ~~vine-core dependency shading~~ → **locked** (2026-09-23): shade + relocate
   under `dev.vineengine.internal.shaded`; zero jar-in-jar divergence across
   loaders.
5. Governance: lightweight RFC process once ≥2 consumers are live on the API.
6. ~~26.x drop policy~~ → **locked**: latest + previous drop (§3).
7. ~~1.21.1 retirement~~ → **locked**: kept long-term (§3).
8. ~~Backend-partner policy~~ → **locked**: GeckoLib format adopted; partners
   hard-required where used; no fallback-renderer obligation (§5.14, §5.20).
9. ~~Authoring model~~ → **locked**: Java + JSON descriptors; scripting layer
   out of scope (§5.2).
10. Descriptor JSON schema governance: versioning + migration rules for
    datapack-authored content (needed once JSON authoring ships).

---

## 12. References

- Minecraft version numbering change: https://www.minecraft.net/en-us/article/minecraft-new-version-numbering-system
- 26.1 "Tiny Takeover" (first unobfuscated release, Java 25): https://www.minecraft.net/en-us/article/minecraft-java-edition-26-1
- 26.3 "Wilderness Bound" (latest, Sep 15 2026): https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3
- 26.2 Snapshot 1 — experimental Vulkan / Graphics API selector: https://feedback.minecraft.net/hc/en-us/articles/44898619266317-Minecraft-Java-Edition-26-2-Snapshot-1
- Fabric for 26.1 (Yarn discontinued, non-remapping loom, Java 25): https://www.fabricmc.net/2026/03/14/261.html
- NeoForge for 26.1 (ModDevGradle, new version format): https://neoforged.net/news/26.1release/
- Game Engine Architecture (Gregory) — runtime gameplay foundation systems: https://www.gameenginebook.com
- Unreal Gameplay Framework (GameMode/GameState pattern): https://dev.epicgames.com/documentation/unreal-engine/game-mode-and-game-state-in-unreal-engine
- Luanti (ex-Minetest): https://www.luanti.org/ · Lua API: https://github.com/luanti-org/luanti/blob/master/doc/lua_api.md · VoxeLibre: https://github.com/VoxeLibre/VoxeLibre
- Stonecutter: https://github.com/kikugie/stonecutter
- ReplayMod preprocessor: https://github.com/ReplayMod/preprocessor · example: https://github.com/Conflux-Union/preprocessor-example-mod
- SpongeAPI: https://github.com/SpongePowered/SpongeAPI · docs: https://docs.spongepowered.org/stable/en/about/introduction.html
- Sinytra Connector: https://connector.sinytra.org/ · https://github.com/Sinytra/Connector
- Architectury: https://github.com/architectury
- GeckoLib (adopted asset format + render backend): https://github.com/bernie-g/geckolib
- VoxelCore (naming collision): https://github.com/MihailRis/voxelcore
- Vineflower (naming adjacency): https://github.com/Vineflower/vineflower
- FTB Quests (quest system precedent): https://github.com/FTBTeam/FTB-Quests
- FTB Teams (party/shared-progress precedent): https://github.com/FTBTeam/FTB-Teams
- Waystones (fast-travel / explicit respawn-binding precedent): https://github.com/TwelveIterations/Waystones
- NeoForge datapack registries (descriptor riding): https://docs.neoforged.net/docs/1.21.1/concepts/registries/
- Fabric dynamic registries: https://docs.fabricmc.net/develop/registries/dynamic-registry
- Curios: https://www.curseforge.com/minecraft/mc-mods/curios · Trinkets: https://github.com/emilyploszaj/trinkets · Accessories (cross-loader): https://modrinth.com/mod/accessories
- JEI: https://github.com/mezz/JustEnoughItems · EMI: https://github.com/emilyploszaj/emi
- labPBR Material Standard (PBR texture convention): https://shaderlabs.org/wiki/LabPBR_Material_Standard · Iris PBR docs: https://shaders.properties/current/how-to/pbr_standards/
- GeckoLib emissive/glow layers (GL4/GL5 — its only native material feature): https://github.com/bernie-g/geckolib/wiki/Emissive-Textures-%28Geckolib5%29 · GL5 changes: https://github.com/bernie-g/geckolib/wiki/Geckolib-5-Changes
- Iris Shaders (official multi-loader; NeoForge build requires Sodium, incompatible with Embeddium): https://github.com/IrisShaders/Iris
- Sodium (official multi-loader): https://github.com/CaffeineMC/sodium · Embeddium (frozen, no 26.x): https://github.com/FiniteReality/embeddium · Oculus (legacy, ≤1.20.1): https://modrinth.com/mod/oculus
