# SUB-17 — Client 3D (models/FX/camera)

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M5 · **Depends on:** SUB-09, SUB-16 · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.11, §5.14, §5.20 (also §5.1, §8, M5) · **Module(s):** vine-client-api | vine-core | vine-spi | drivers

## 1. Purpose

The deferred client-3D surface (§5.11): retained-mode model/material
descriptors cooked per-version by drivers, GeckoLib render-backend wiring,
particle/FX descriptors with spawn budgets, a camera directive API,
shader/lightmap probes, part-state overlays from sub-08 part `VoxelData`, and
the 3D **donation intake** graduating `@VineUnsafe` prototypes into official
API. Non-goals: immediate-mode rendering (never), a VINE-native fallback
renderer (§5.14 locked), post-processing, 2D (sub-16).

**DESIGNED now vs IMPLEMENTED at M5:** stages A–B land pre-M5 (probe enum,
dormant `@Incubating` skeleton, donation intake) so consumers can query and
prototype per §5.11; C–E are M5. Nothing renders before M5 — every probe
returns `false` until then.

## 2. Design

**API** (`vine-client-api`, `dev.vineengine.vine.client3d`) — pure-data
descriptors with `Codec`s (sub-02; Java + JSON paths). Consumers describe;
drivers cook:

```java
public record ModelDescriptor(VineId id, ModelKind kind, VineId geometry, VineId texture,
    Map<String,VineId> stateTextures, RenderLayerHint layerHint,
    Optional<MaterialMaps> materials) implements DescriptorType<ModelDescriptor> {
  public static final Codec<ModelDescriptor> CODEC = /* sub-02 machinery */; }
public enum ModelKind { STATIC_BLOCK, STATIC_ITEM, GEO_ANIMATED } // kind picks pipeline, never renderer
public enum RenderLayerHint { SOLID, CUTOUT, TRANSLUCENT, EMISSIVE }
public record MaterialMaps(VineId normal, VineId specular) {} // labPBR 1.3 channels: _n = normalXY+AO+height, _s = smoothness+F0+porosity/SSS+emissive; authored as separate source maps, never Bedrock MER packing
public record ParticleDescriptor(VineId id, ParticleShape shape, VineId texture, int maxLifetimeTicks,
    SpawnBudget budget) implements DescriptorType<ParticleDescriptor> {} // vanilla-mapping first, else custom
public record SpawnBudget(int maxPerTickPerSource, int maxAlivePerPlayer) {}
public sealed interface CameraDirective permits CameraShake, FocusLock, Letterbox, ThirdPersonView {
  int priority(); Duration ttl(); } // higher wins, ties expire earliest; ttl: server never holds the camera
// 3D probes are constants of sub-16's ClientFeature enum — the single client
// probe enum (implements sub-01's Feature) — appended by this file's Stage A:
// RETAINED_MODELS, GECKO_BACKEND, CUSTOM_PARTICLES, CAMERA_DIRECTIVES,
// PART_STATE_OVERLAYS, SHADER_HOOKS, LIGHTMAP_OVERRIDE, PBR_MATERIALS
```

Gating is always `VineEngine.supports(ClientFeature.X)` (sub-01), never a
version check. Vulkan-era line (§5.11): **allowed** anywhere — retained-mode
descriptor registration; **forbidden** outside drivers and non-`@VineUnsafe`
consumer code — `RenderSystem.*`, `PoseStack`/matrix stacks, `RenderType.*`,
GL/Vulkan handles, GeckoLib types.

**Advanced materials (PBR-aware, probe-gated — verified 2026-09-23).** Material
data is authored once in the **labPBR 1.3** convention (the Iris/OptiFine
community standard) and always rides the descriptor (`MaterialMaps`); rendering
is gated on `ClientFeature.PBR_MATERIALS`: shader chain present (locked primary
stack: **Sodium + Iris** — both officially multi-loader on 1.21.1 + 26.x;
Iris-NF requires Sodium, declares Embeddium incompatible; OptiFine optional;
the official Vibrant Visuals Java spec absorbed by the 26.x driver when
published — no API change) ⇒ full PBR, otherwise vanilla-lighting fallback.
On the GeckoLib backend, the emissive channel cooks to `_glowmask` +
`AutoGlowingGeoLayer` — GeckoLib's only native material feature (GL4/GL5 have
no normal/specular support); guaranteed no-shader PBR is custom-renderer work,
`@VineUnsafe` territory until Vibrant Visuals Java stabilizes.

**Internals** (`vine-core`, `…internal.client3d`): `Client3dRuntime` — dormant
registry, zero hooks until a consumer registers (Minimal Footprint).
`OverlayResolver` — maps part `VoxelData` state (`wound: deep`) to
`stateTextures` variants; state is server-owned, texture choice is client
presentation of combat output (§5.15). `CameraArbiter` — client-owned:
directives arrive as server *suggestions* (sub-05), applied by priority/ttl;
player accessibility settings win; foreign mods get the frame, never a
per-frame fight.

**Driver contract** (`vine-spi`):

```java
public interface Client3dDriver {
  CookedModel cook(ModelDescriptor d, AssetKitchen kitchen); // per-version assets at registry-freeze
  ParticleHandle materialize(ParticleDescriptor d);
  void applyCamera(CameraDirective active); boolean probe(ClientFeature f); }
```

- **1.21.1 (NF + Fabric):** GeckoLib **GL4** (hard dep, §5.14/§5.20); cook
  emits blockstate/model JSON + override-style item JSON; layers via loader
  hooks (NF render-type events / Fabric render-layer map), in-driver only.
- **26.x (NF + Fabric):** GeckoLib **GL5**; cook emits modern item-model
  definitions; render-layer registration is the churn axis — spike at Stage C,
  absorb per drop; Java 25; the 26.2-era Graphics API selector surfaces via
  sub-01's `vulkanRenderer` probe, no API change. Loader axis differs only in
  registration entry points; cooked assets identical within a version axis.

**Data flow:** server selects animation/part state (sub-08/09) → syncs state
IDs → client renders cooked assets + overlays. Camera: server proposes, client
arbiter disposes.

## 3. Stages

A–B pre-M5 (design-now); C–E at M5 after sub-09 render polish begins. C ∥ D.
Bootstrap prompts rely on this file's own sections — self-contained by
contract; each executes that stage's Do + Acceptance.

### Stage A — Probe skeleton & dormant API (pre-M5)

- [ ] **Do:** the §2 3D probe constants on sub-16's `ClientFeature` enum,
  §2 records, `Client3dDriver` probing `false` everywhere; all `@Incubating`;
  no hooks. **Acceptance:** TCK probe-matrix green on all cells
  (acceptance-matrix rule: the two 1.21.1 cells pre-M4, all four post-M4);
  shared modules Java 21. **Touches:** vine-client-api, vine-spi, drivers
  (stub), vine-tck.
- **Bootstrap prompt:**
  > Execute sub-17 Stage A of docs/subsystems/sub-17-client-3d.md: create
  > `dev.vineengine.vine.client3d` (vine-client-api) with the §2 types as
  > Codec records (sub-02 pattern) and `Client3dDriver` (vine-spi) probing
  > false, all `@Incubating`. Acceptance as staged above; vine-client-api must
  > hold zero `net.minecraft` refs. Conventions: docs/README.md.

### Stage B — Donation intake process (pre-M5, continuous)

- [ ] **Do:** manifest flag name for released mods shipping `@VineUnsafe` 3D
  prototypes; proposal template (concept API sketch + per-version prototype
  evidence + demand note); §6 graduation checklist. **Acceptance:** dry-run
  review of a hypothetical prototype fills every checklist line. **Touches:**
  `docs/` intake files only; no code.
- **Bootstrap prompt:**
  > Execute sub-17 Stage B of docs/subsystems/sub-17-client-3d.md: author the
  > proposal template (concept API with no GL types, the per-version
  > `@VineUnsafe` prototype, which consumer needs it) and the §6 checklist.
  > Acceptance: dry-run review of a sample camera-shake prototype completes
  > every line. No engine code.

### Stage C — Model cooking & GeckoLib backend (M5)

- [ ] **Do:** `cook` in both drivers per §2 per-cell notes; GEO_ANIMATED →
  GeckoLib registration with animation-ID handoff from sub-09 server-approved
  state; `RenderLayerHint` → per-cell layer registration; cook-time validation
  naming offending `VineId`s; boot presence check with explicit
  hard-dependency failure; materials — labPBR `_n`/`_s` maps cooked per cell,
  the emissive channel cooked to GeckoLib `_glowmask` on GEO_ANIMATED.
  **Acceptance:** TCK model-cook (static block +
  item, Java and JSON paths) error-free on ≥2 drivers (one per loader family);
  testmod animated entity plays a server-selected animation on 4 cells;
  GeckoLib-absent boot fails naming it. **Touches:** vine-core
  (`AssetKitchen`), vine-spi, drivers, vine-tck, testmod.
- **Bootstrap prompt:**
  > Execute sub-17 Stage C of docs/subsystems/sub-17-client-3d.md per its §2
  > per-cell notes and plan §5.14/§5.20: implement `Client3dDriver.cook`
  > (spike the 26.x render-layer mechanism first, findings in driver javadoc)
  > and bind GEO_ANIMATED models to GeckoLib; consumers hand off sub-09
  > animation IDs, never GeckoLib types; GeckoLib absent ⇒ boot failure naming
  > it. Acceptance as staged above; GL/GeckoLib types stay in driver packages.

### Stage D — Particle/FX descriptors & budgets (M5)

- [ ] **Do:** `materialize` (vanilla-mapping first, custom only when no vanilla
  fit); drop-on-exhaustion token budget for `SpawnBudget` in vine-core;
  client-spawn packet over sub-05. **Acceptance:** TCK fx-budget (alive-count
  caps) on ≥2 drivers; 200 concurrent budgeted emitters within the weakest
  cell's frame budget, numbers recorded. **cannot tell (no artifact):** no
  fx-budget run exists and no cell has been chosen for it — the emitter count,
  the cell and that cell's frame budget are fixed when Stage D runs at M5.
  **Touches:** vine-core (FX runtime), drivers, vine-tck.
- **Bootstrap prompt:**
  > Execute sub-17 Stage D of docs/subsystems/sub-17-client-3d.md:
  > `ParticleDescriptor` materialization (vanilla first, custom fallback),
  > `SpawnBudget` as drop-on-exhaustion token budget, spawn sync over sub-05.
  > Acceptance as staged above, on ≥2 drivers (one per loader family).

### Stage E — Camera, overlays, shader probes (M5)

- [ ] **Do:** `CameraArbiter` + server-suggestion packet + driver camera hooks
  for all four directive kinds; `OverlayResolver` texture swaps; honest
  `SHADER_HOOKS`/`LIGHTMAP_OVERRIDE` probes (likely `false` at M5, §4).
  **Acceptance:** TCK camera-negotiation (priority + tie-break + ttl expiry
  restores vanilla camera) and wound-overlay on ≥2 drivers; probes truthful.
  **Touches:** vine-core (arbiter, resolver), vine-spi, drivers, vine-tck.
- **Bootstrap prompt:**
  > Execute sub-17 Stage E of docs/subsystems/sub-17-client-3d.md:
  > client-owned `CameraArbiter` (priority/ttl, player settings win,
  > foreign-mod yield), server-suggestion packet, driver hooks for
  > shake/focus-lock/letterbox/third-person, `OverlayResolver` mapping sub-08
  > part `VoxelData` to `stateTextures`. Acceptance as staged above, on ≥2
  > drivers (one per loader family); smoke checklist updated.

## 4. Problems & blockers

- **Vulkan-era uncertainty** (experimental Vulkan in 26.2, Vibrant Visuals
  undated): structural mitigation — retained-mode concepts only, no GL/
  RenderSystem/Vulkan types outside drivers; sub-01's `vulkanRenderer` probe
  reports the active pipeline; a Vulkan default is absorbed behind an
  unchanged SPI.
  Owner: driver leads at the M5 spike.
- **26.x render-layer churn:** spike at Stage C; per-drop conditionals stay in
  the 26.x driver, javadoc naming the drop that changed the mechanism.
- **GeckoLib GL4→GL5 drift:** locked (§5.14/§5.20) — hard dep where it ships,
  drift quarantined per cell, no fallback owed; GeckoLib skipping a drop ⇒
  `GECKO_BACKEND=false` + explicit GEO_ANIMATED cook failure, never silent.
- **Particle perf:** budgets are the contract; Stage D's perf scenario pins
  per-cell numbers so regressions fail CI instead of shipping.
- **PBR reality check (verified 2026-09-23):** GeckoLib natively supports only
  emissive glowmasks; normal/specular/PBR maps are shader-chain-dependent
  (locked stack: Sodium + Iris, labPBR-reading; entity-pass support varies —
  known GeckoLib×shader issues include entity-shadow animation freezes and
  missing reflections on GL renderers; GeckoLib 4.6+ absorbed the Iris/Oculus
  PBR-compat fix, the old geckoanimfix addon is deprecated). Vibrant Visuals
  Java PBR spec is unpublished. Mitigation: data always carried, rendering
  probe-gated (`PBR_MATERIALS`); cooked packs declare `format=lab-pbr/1.3`;
  test matrix per cell: shaders-off baseline → Sodium+Iris → OptiFine optional.
- **Camera vs foreign mods:** a foreign hook wins the frame while the
  directive ticks its ttl; among VINE consumers, priority + tie-break is
  deterministic and TCK-tested.
- **Model format beyond GeckoLib:** locked here — GEO_ANIMATED is GeckoLib geo
  JSON only; STATIC_BLOCK/ITEM is a VINE subset of Blockbench block/item
  models both axes express, cooked per version. No third format.
- **Shader/lightmap hooks:** honest unknown pre-Vulkan-settlement — M5 ships
  truthful probes (probably `false`), not a speculative API; Stage B donations
  are the designed path in.

## 5. Verification

TCK scenarios owned (each green on ≥2 drivers, one per loader family, before
its surface merges — §8): **probe-matrix** (per-cell truth table; all `false`
pre-M5) · **model-cook** (Java + JSON paths, 4 cells) · **fx-budget** (caps +
a 200-emitter frame run — **cannot tell (no artifact)**: no run exists and the
cell plus its frame budget are chosen when Stage D runs at M5) · **camera-negotiation** (priority, tie-break, ttl,
foreign-yield simulation) · **wound-overlay** (part `VoxelData` change ⇒
texture swap). Client smoke additions per cell: animated entity plays
server-selected animation; static models render in-world/in-hand; camera
directives visibly apply and release; GeckoLib-absent boot message.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.client3d` (public) /
  `…internal.client3d` (core, SPI, drivers); shared modules Java 21, 26.x
  drivers Java 25; descriptors are data (Java + JSON, sub-02 Codecs).
- **Comment policy:** javadoc states *why* and invariants ("server suggests,
  client owns" on `CameraDirective`); driver comments name the exact
  drop/loader mechanism absorbed.
- **Forbidden:** version-string parsing (probes only, §5.12); loader, GeckoLib,
  RenderSystem, GL, or Vulkan types outside drivers; Mixins outside drivers
  (§5.9); hooks active without consumers (§5.1); tests pinning cooked asset
  bytes instead of observable behavior.
- **Donation review checklist (Stage B owns; reused at every graduation):**
  manifest flag present · prototype exercised on ≥1 cell per loader family ·
  proposed API is retained-mode data only · TCK scenario drafted · ≥2-driver
  plan · `@Incubating` one minor cycle before `@Stable`.
- **Done means:** all checkboxes ticked, acceptance green on 4 cells, probes
  truthful, status `done`, dashboard row updated in `docs/README.md`.
