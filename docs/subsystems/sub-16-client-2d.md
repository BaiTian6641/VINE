# SUB-16 — Client 2D (GUI/HUD)

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M2→M3 · **Depends on:** SUB-01, SUB-05 · **Blocks:** SUB-17
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.11 (+ §5.6, §5.20) · **Module(s):** vine-client-api, vine-core, vine-spi, drivers

## 1. Purpose

The engine's 2D client half (§5.11): screen descriptors materialized as per-cell
native `Screen` delegates, a widget tree + layout model, a HUD overlay registry, the
consumer-facing **keybind surface** (client half of sub-10's actions), text/theme
primitives, and menu↔server data sync over sub-05 channels. Non-goals: 3D rendering
(sub-17), audio playback (sub-12), recipe-viewer internals (bridges are sub-20; this
file only declares exclusion zones), animation rendering (sub-09).

## 2. Design

- **API surface** (`vine-client-api`, `dev.vineengine.vine.client.ui`):

```java
/** Declarative screen; the driver materializes the native Screen delegate. */
public record ScreenDescriptor(VineId id, Widget root, VineId theme,
    boolean pausesGame, Set<VineId> exclusionZones) {  // recipe-viewer zones, sub-20
  public static final Codec<ScreenDescriptor> CODEC = ...; }

/** Retained-mode widget; layout resolved in logical px by the engine solver. */
public sealed interface Widget permits Button, Label, Slot, Grid, Custom {
  LayoutSpec layout(); }

/** HUD overlay; engine owns layer order + visibility toggles. */
public record HudLayer(VineId id, Anchor anchor, int zOrder, boolean defaultVisible) {
  public static final Codec<HudLayer> CODEC = ...; }

/** Consumer keybind; actionId binds to a sub-10 ActionDescriptor. */
public record KeybindDescriptor(VineId id, VineId category, Key defaultKey,
    VineId actionId) {}

/** §5.11 probes; until a surface lands, supports() is the only contract.
    This file OWNS the client probe enum (README vocabulary); Feature is
    sub-01's interface (String ids). HUD probe constant is `HUD` — sub-22
    gates its HUD exemplar on `ClientFeature.HUD`. */
public enum ClientFeature implements Feature { SCREENS, HUD, KEYBINDS, THEMES, NINE_SLICE, MENU_SYNC }

public record Theme(VineId id, TextStyle baseText, NineSlice chrome, int guiScaleHint) {}
```

- **Internals** (`vine-core`, headless where possible): retained widget tree + layout
  solver (anchors, percent/px constraints, logical-px units — GUI-scale is a driver
  input, never read by consumers); HUD registry = z-ordered layer list + per-layer
  visibility; menu-sync sessions over sub-05: **server owns menu state**, client
  sends intents, server replies with state snapshots (server-authoritative
  pattern — §4 of this file).
- **Driver contract** (`vine-spi`): screen lifecycle delegates (open/init/tick/
  render/close), HUD render hook, keybind registration, font metrics + GUI-scale
  query, nine-slice blitter. 1.21.1-NF: `ScreenEvent.Opening/Init/Render.Pre/Post`,
  `RegisterGuiLayersEvent`; 1.21.1-Fabric: `ScreenEvents`, `HudRenderCallback`,
  `KeyBindingHelper`. Rendering is `GuiGraphics`-based on both 1.21.1 cells; 26.x
  render churn (Vulkan transition, §5.11) is absorbed behind `ClientFeature` probes
  — drivers degrade to no-render + `supports()==false` rather than crash.
- **Sync:** one sub-05 channel per menu session: client→server intents, server→client
  snapshots with per-tick checksum; desync → full resync. Keybind presses fire
  sub-10 `ActionRequest`s — no separate input path.

## 3. Stages

### Stage A — Screen descriptors & delegate SPI (M2)

- [ ] **Do:** `ScreenDescriptor` + `Codec`; driver SPI `openScreen(descriptor)`; minimal widget set (Label/Button).
- **Acceptance:** identical descriptor opens a working screen on 1.21.1-NF + Fabric; open/close logged.
- **Touches:** vine-client-api, vine-spi, driver-1.21.1-neoforge, driver-1.21.1-fabric.
- **Bootstrap prompt:**
  > Implement sub-16 Stage A per this file + docs/README.md (Java 21, Prime Invariant; client API in `dev.vineengine.vine.client.ui`). Screen lifecycle: NF `ScreenEvent.*`, Fabric `ScreenEvents`. Acceptance: testmod screen opens on both 1.21.1 cells from one descriptor.

### Stage B — Widget tree & layout solver (M2)

- [ ] **Do:** sealed `Widget` hierarchy; layout solver (anchors, constraints, logical px); focus/keyboard navigation.
- **Acceptance:** golden fixtures: JSON widget tree → computed rects identical across both 1.21.1 cells and a driver-free JVM run.
- **Touches:** vine-client-api, vine-core `internal.ui`.
- **Bootstrap prompt:**
  > Implement sub-16 Stage B: solver is engine-owned (vine-core), drivers supply only font metrics + GUI scale. Acceptance: golden rect fixtures pass on JVM + both 1.21.1 cells. Conventions docs/README.md.

### Stage C — HUD overlay registry (M2)

- [ ] **Do:** `HudLayer` registry (anchors, zOrder, visibility toggles); one driver render hook per cell rendering all VINE layers in zOrder.
- **Acceptance:** two testmod layers render in declared order, toggle at runtime; foreign-mod HUD untouched (Minimal Footprint probe).
- **Touches:** vine-client-api, vine-core, drivers.
- **Bootstrap prompt:**
  > Implement sub-16 Stage C: NF `RegisterGuiLayersEvent`, Fabric `HudRenderCallback`; VINE layers sort by zOrder inside a single engine hook. Acceptance: layering + toggle smoke on both 1.21.1 cells. Conventions docs/README.md.

### Stage D — Keybind surface (M3; needs sub-10 Stage A)

- [ ] **Do:** `KeybindDescriptor` registration; driver keybind delegates; press → sub-10 `ActionRequest`.
- **Acceptance:** keybind press produces a validated action request end-to-end (client smoke, both 1.21.1 cells).
- **Touches:** vine-client-api, drivers.
- **Bootstrap prompt:**
  > Implement sub-16 Stage D: registration NF key-mapping event / Fabric `KeyBindingHelper`; presses call the sub-10 client action API — never send packets directly. Acceptance: keybind→request round-trip on both 1.21.1 cells.

### Stage E — Text, theme, nine-slice (M3)

- [ ] **Do:** `Theme`/`TextStyle`/`NineSlice` descriptors (datagen-cooked assets, §5.2 pipeline); theme application + hot-reload where the cell allows.
- **Acceptance:** testmod screen reskins via JSON theme swap without code change, on both 1.21.1 cells.
- **Touches:** vine-client-api, vine-core, drivers.
- **Bootstrap prompt:**
  > Implement sub-16 Stage E: nine-slice is the default chrome path; `Custom` widget is the custom-render escape. Acceptance: JSON theme swap reskins the testmod screen on both 1.21.1 cells. Conventions docs/README.md.

### Stage F — Menu sync, probes, exclusion zones (M3)

- [ ] **Do:** menu-sync sessions over sub-05 (intents/snapshots/checksum resync); `ClientFeature` probe wiring; exclusion-zone declaration consumed by sub-20's JEI/REI/EMI bridges.
- **Acceptance:** TCK: server flips menu state → client digest matches on both 1.21.1 cells; exclusion zones queryable by a stub viewer bridge; `supports(ClientFeature.*)` answers per cell.
- **Touches:** vine-client-api, vine-core `internal.ui.sync`, drivers.
- **Bootstrap prompt:**
  > Implement sub-16 Stage F: server owns menu state (§4 of this file); probes are data, never version strings (§5.12). Coordinate the exclusion-zone contract with sub-20 via hub before editing. Acceptance: the three listed checks green on both 1.21.1 cells.

*(26.x cells: port each stage's driver half during the M4 wave; design is
cell-neutral, render path probe-guarded.)*

### Stage A — Model and layout solver (landed, engine-side)

- [x] **Do:** `LayoutSpec` + `Anchor`, the sealed `Widget` vocabulary (button, label, slot,
  group), `ScreenDescriptor`, `HudLayer`, and the `ClientFeature` probes the engine relies on;
  `UiLayout` resolves a screen to absolute rectangles from the descriptor alone.
- **Evidence:** the headless `ui.layout.txt` golden — the authored screen parsed through its own
  codec, resolved at two logical screen sizes.
- **Corrected by the golden:** the first solver multiplied sizes by the GUI scale while taking
  the screen size in logical pixels, which is how a menu is correct at scale 2 and off the
  screen at scale 3. Everything is logical pixels now; a cell reports its screen size the way
  vanilla does.
- **Remaining:** the per-cell materialization (native screens, HUD drawing, keybinds) and the
  screenshot pass. A scripted client run exists on both cells (`vineTckClient`, see
  `docs/QUICKSTART.md`), and its `open_screen` step is *refused loudly* today — deliberately:
  the runner may not pretend a screen opened when no cell can materialize one yet.

## 4. Problems & blockers

- **Screen scaling/accessibility:** solver works in logical px only; GUI scale +
  font metrics are driver queries. Accessibility (text scaling, contrast) rides the
  `Theme` — no per-widget hacks. Decision owner: this file.
- **Nine-slice vs custom:** nine-slice chrome is the default (cooked by the §5.2
  content pipeline); truly custom rendering goes through the `Custom` widget, which
  is probe-gated on 26.x. Do not add a third path.
- **HUD ordering vs other mods:** VINE renders all its layers inside one loader hook per cell; foreign HUDs are never reordered (Minimal Footprint). Known risk: NF gui-layer ordering vs vanilla layers — drivers document their anchor layer; conflicts surface in client smoke, not code.
- **Server-authoritative menu sync:** server owns state; client sends intents only;
  per-tick checksum, full resync on mismatch. No optimistic client mutation of
  server-owned fields — consumer B's GUI-heavy exemplar (§10.1) is the validation
  track.
- **26.x render churn:** `GuiGraphics` is not assumed stable; every render call sits
  behind a driver SPI method so the 26.x driver can swap backends without API change.

## 5. Verification

Owned TCK scenarios (§7): screen-descriptor parity (open + layout digest identical,
both loaders); layout golden fixtures; HUD layering/visibility; keybind→action
round-trip (with sub-10 Stage B); menu-sync digest scenario. Client smoke per cell:
screen open/close, HUD toggles, theme swap, exclusion zone respected with a recipe
viewer present. Rule: no surface ships without ≥2 drivers, one per loader family.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.client.ui` (API) / `dev.vineengine.vine.internal.ui`
  (core, drivers); shared modules Java 21, 26.x drivers Java 25. Retained-mode
  descriptors; drivers are delegates, never policy.
- **Comment policy:** javadoc states why + invariants ("server owns menu state");
  driver comments name the exact lifecycle event absorbed.
- **Forbidden:** version-string parsing (probes only, §5.12); loader/`net.minecraft`
  types in API; Mixins outside drivers; direct GL/RenderSystem calls in shared code
  (§5.11 pins concepts, not GL).
- **Done means:** checkboxes ticked, TCK + client smoke green on covered cells,
  status `done`, dashboard row updated in `docs/README.md`.
