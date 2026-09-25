# SUB-20 — Ecosystem bridges & coexistence

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** Continuous · **Depends on:** SUB-02, SUB-04 · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.20 · **Module(s):** vine-api | vine-core | vine-spi | drivers

## 1. Purpose

Own the three cooperation directions of §5.20: (1) **backend partner bridge
modules** below the SPI (GeckoLib, JEI/REI/EMI, Curios/Trinkets/Accessories,
loader energy/fluid APIs) with classloading isolation and presence probing;
(2) **extension-point registry patterns + docs** so consumers extend behavior
nodes, capability types, descriptor types, combat stages, quest types, and
session types without touching engine code; (3) **coexistence guarantees**
(Minimal Footprint §5.1, native-strategy data interop §5.4, inter-consumer
interop via `VineId`s). Non-goals: capability semantics (sub-04), the
GeckoLib asset format/evaluator (sub-09), registry machinery itself
(sub-01/sub-02) — this file owns bridge modules, probing, patterns; per-cell
packaging lives in the driver files (sub-18/sub-19).

## 2. Design

**Bridge module organization** (binding isolation pattern):

```
drivers/driver-1.21.1-neoforge/
├─ src/main/java/dev/vineengine/vine/internal/nf1211/…      ← driver core
└─ src/bridge/java/dev/vineengine/vine/internal/bridge/     ← isolated source set
   ├─ geckolib/GeckoLibBridge.java   + GeckoLib4Adapter.java
   ├─ curios/CuriosBridge.java       + CuriosAdapter.java
   ├─ jei/JeiBridge.java             + JeiAdapter.java
   └─ energy/FeBridge.java           + FeAdapter.java
```

**Two-class rule.** Every bridge = a *probe class* (constant pool contains
**no partner types**) + an *adapter class* (references the partner API,
classloaded only after the probe passes, reached exclusively via
`Class.forName` — never imported by driver core):

```java
// vine-spi — dev.vineengine.vine.internal.bridge
interface VineBridge {
    VineId id();                 // e.g. vine:bridge/jei
    String partnerModId();       // "jei", "curios", "geckolib"
    boolean isPartnerPresent();  // loader mod-list query ONLY
    String adapterClassName();   // string, not Class<?>
}
// driver boot: if (b.isPartnerPresent())
//     Class.forName(b.adapterClassName()).getConstructor().newInstance();
```

Probe fails ⇒ one boot log line (`vine: bridge jei inactive — partner
absent`) and the adapter class is never loaded; the TCK asserts this by
scanning loaded classes for partner packages. Partner *version* drift is
quarantined in **version-locked adapters** (`GeckoLib4Adapter` on 1.21.1,
`GeckoLib5Adapter` on 26.x) selected by probing the partner's own capability
API — never version strings (§5.12). Loader metadata: NeoForge
`neoforge.mods.toml` `[[dependencies]]` `type="optional"` with `after:`/
`before:` ordering; Fabric has **no** cross-mod ordering metadata
(`depends`/`breaks` are presence constraints, not ordering — §5.20), so
Fabric bridges use deferred activation (custom entrypoints /
`FabricLoader#getEntrypointContainers`), fired at the engine `VINE_BOOT`
phase (sub-01), after every loader entrypoint — ordering becomes
engine-owned.

**Hard vs soft.** GeckoLib is a **hard dependency on cells where it ships**
(locked, §5.14): compile-time dependency, `required` metadata, clean boot
failure when absent; this file owns the probing pattern only — per-cell
packaging metadata is sub-18/sub-19's. All other partners are soft: absence
degrades capability via sub-01 `Feature` probes —
`VineEngine.supports(BridgeFeature.RECIPE_VIEWER)`; `BridgeFeature` is this
file's probe enum (`implements Feature`, String ids, one constant per soft
bridge), never breaks a consumer.

**Named bridge map:**

| Partner | 1.21.1-NF | 1.21.1-Fabric | 26.x-NF | 26.x-Fabric |
|---|---|---|---|---|
| GeckoLib | GL4, hard | GL4, hard | GL5, hard | GL5, hard |
| Combat | Better Combat (probe-only, soft) | Better Combat (probe-only, soft) | Better Combat (soft) | Better Combat (soft) |
| Combat (NF-only) | Epic Fight (soft) | — | Epic Fight (soft, if it ships) | — |
| Recipe viewer | JEI | REI | JEI or EMI | EMI (+ its JEI-compat) |
| Accessory slots | Curios | Trinkets | Accessories | Accessories |
| Energy/fluid | FE/RF caps | Fabric transfer API | FE/RF caps | Fabric transfer API |
| Shader/render stack | Sodium+Iris (probe-only) | Sodium+Iris (probe-only) | Sodium+Iris (probe-only) | Sodium+Iris (probe-only) |

Combat partners are **ownership-based**, not merged (verified 2026-09-26):
Better Combat ships on both 1.21.1 loaders and is alive on 26.2, so it is the
parity-safe partner; Epic Fight is NeoForge-only on 1.21.1 with a self-described
stabilizing API, so no Fabric cell may depend on it. For a partner-owned weapon the
engine installs no sweep and no cooldown and cooks the partner's own preset
(`data/<ns>/weapon_attributes/<item>.json` for Better Combat) — sub-10 §2 owns the
rule, this file owns the probe.

GL5's generation boundary is also a *namespace* boundary: GeckoLib 5 moved
from `software.bernie.geckolib` to `com.geckolib` (verified 2026-09-25,
wiki.geckolib.com/docs/geckolib5 + modrinth.com/mod/geckolib version table:
26.1 → 5.5, 26.1.2 → 5.5.2, 26.2 → 5.5.6, Fabric and NeoForge both listed).
`GeckoLib5Adapter` therefore probes `com.geckolib` only, and the 26.x hard-dep
metadata names that coordinate; the two adapters never share a probe class name,
which is what keeps "partner absent" and "partner present with a different
generation" distinguishable at boot.

Sodium + Iris is a **probe-only coexistence partner** — no bridge module, no
code integration: presence flips `PBR_MATERIALS`-class probes (sub-16/sub-17);
labPBR assets and render-pass behavior are the contract (sub-17). Locked
primary shader stack (2026-09-23): officially multi-loader on 1.21.1 + 26.x;
Iris-NeoForge requires Sodium and declares Embeddium incompatible; Oculus is
legacy (≤1.20.1), Embeddium frozen (no 26.x port).

EMI ships a JEI-compat layer: probe order native EMI → JEI-compat → none,
register exactly once (no double registration). Viewer bridges implement the
`RecipeViewerAdapter` SPI owned by sub-11. Viewer bridges reciprocate
sub-16's exclusion-zone contract: when a recipe viewer renders, it respects
the HUD/screen `exclusionZones` a consumer `ScreenDescriptor` declares
(sub-16 Stage F). Accessory/energy bridges write
into the sub-04 capability registry; with no partner present the
**engine fallback store** serves the capability — the portability floor
(§5.5): capability always works, interop is best-effort.

**Extension-point registries.** One canonical pattern over the sub-01
registry core + sub-02 descriptor machinery, applied to all six points
(behavior nodes §5.13, capability types §5.5, descriptor types §5.2, combat
pipeline stages §5.15, quest objective/reward types §5.21, session types
§5.19):

```java
VineRegistries.register(ExtensionPoints.BEHAVIOR_NODES, id, nodeType); // sub-02 API
VineRegistries.register(ExtensionPoints.SESSION_TYPES, id, descriptor);
```

Each point: a `VineId`-keyed registry frozen at `REGISTRIES_FROZEN`, plus a
`Codec`'d descriptor where data-driven; each `ExtensionPoints.*` constant is
a sub-02 `DescriptorType`. Inter-consumer interop rides `VineId`
strings only — consumer B references consumer A's content with no direct
dependency.

**Coexistence.** Bridges stay dormant unless consumer content uses the
capability; foreign block/entity data interop goes through native-strategy
`VoxelData` fields (§5.4 opt-in) — never global patches; Mixins remain
driver-quarantined (§5.9).

## 3. Stages

### Stage A — Bridge framework + GeckoLib hard-dep probing

- [ ] **Do:** `VineBridge` SPI in `vine-spi`; probe/activate boot sequence at
  `VINE_BOOT`; two-class rule enforced by a TCK classloading assertion;
  GeckoLib hard-dependency presence probe + explicit boot failure (per-cell
  packaging metadata is owned by sub-18/sub-19).
- **Acceptance:** driver boots with and without a dummy partner jar; correct
  log lines both ways; no partner class loaded when absent.
- **Touches:** `vine-spi` bridge package, all drivers' boot + metadata.
- **Bootstrap prompt:**
  > Implement SUB-20 Stage A (read `docs/README.md` conventions + plan §5.20
  > first). Add `VineBridge` to `vine-spi` per sub-20 §2 (two-class rule,
  > `Class.forName` activation at `VINE_BOOT`), wire it in all four drivers,
  > add the GeckoLib hard-dep presence probe with explicit boot failure
  > (packaging metadata per sub-18/sub-19). Acceptance: dummy partner
  > present/absent yields correct boot log lines; TCK classloading assertion
  > passes.

### Stage B — Accessory bridges + fallback floor

- [ ] **Do:** Curios bridge (1.21.1-NF), Trinkets bridge (1.21.1-Fabric),
  Accessories bridge (26.x both); engine fallback store as portability floor
  inside sub-04's registry.
- **Acceptance:** TCK `accessory_fallback_roundtrip`: equip/read-back yields
  identical capability data with the partner present and absent.
- **Touches:** driver bridge source sets, `vine-core` fallback store.
- **Bootstrap prompt:**
  > Implement SUB-20 Stage B: Curios/Trinkets/Accessories bridges per the §2
  > map behind the Stage A framework, plus the engine fallback accessory
  > store in `vine-core`. Acceptance: TCK `accessory_fallback_roundtrip`
  > green with and without each partner.

### Stage C — Energy/fluid interop bridges

- [ ] **Do:** FE/RF bridge (NeoForge cells), Fabric transfer API lookup
  bridge (Fabric cells); both write into the sub-04 energy capability.
- **Acceptance:** TCK `energy_interop`: energy pushed into a VINE capability
  is visible to a partner block and vice versa, per cell.
- **Touches:** driver bridge source sets; no `vine-api` change.
- **Bootstrap prompt:**
  > Implement SUB-20 Stage C: FE/RF and Fabric-transfer bridges into the
  > sub-04 energy capability, behind Stage A probing. Acceptance: TCK
  > `energy_interop` round-trip on one NeoForge + one Fabric cell.

### Stage D — Recipe-viewer bridges

- [ ] **Do:** JEI plugin module, REI plugin module, EMI per-cell decision
  (native vs its JEI-compat) with the §2 probe order; each bridge implements
  the `RecipeViewerAdapter` SPI (owned by sub-11, Stage E); testmod recipe
  category surfaced through each viewer.
- **Acceptance:** testmod category visible in each viewer present in the dev
  runtime per cell; viewer absent ⇒
  `VineEngine.supports(BridgeFeature.RECIPE_VIEWER)` false,
  no crash, no partner class loaded.
- **Touches:** driver bridge source sets, `vine-testmod`.
- **Bootstrap prompt:**
  > Implement SUB-20 Stage D: JEI/REI/EMI bridges per the §2 map and probe
  > order (native EMI → JEI-compat → none; register once). Acceptance:
  > testmod category visible per cell dev runtime; absence degrades via
  > feature probe cleanly.

### Stage E — Extension-point registry patterns + docs

- [ ] **Do:** the six §2 extension points wired on the sub-01 registry core;
  `ExtensionPoints` constants in `vine-api`; consumer guide
  `docs/extension-points.md`; testmod exemplar registering a custom session
  type + capability type.
- **Acceptance:** exemplar registers and functions on ≥2 drivers (one per
  loader family); late registration after `REGISTRIES_FROZEN` fails loudly.
- **Touches:** `vine-api` `ExtensionPoints`, `vine-core` registry wiring,
  `vine-testmod`, `docs/extension-points.md`.
- **Bootstrap prompt:**
  > Implement SUB-20 Stage E. Wire the six §2 extension points on the sub-01
  > registry core with freeze-at-`REGISTRIES_FROZEN`, expose `ExtensionPoints`
  > in `vine-api`, write `docs/extension-points.md`, add the testmod
  > exemplar. Acceptance: exemplar works on ≥2 drivers; late registration
  > errors clearly.

## 4. Problems & blockers

- **Partner API drift:** quarantined in version-locked adapters; a new
  partner major = a new adapter class, consumer surface unchanged (GL4→GL5
  precedent); selection probes the partner's capability API, never strings.
- **Partner abandonment:** contingency is a VINE-native backend behind the
  same SPI (the fallback store proves this for accessories/energy); for
  GeckoLib a native renderer is explicitly **not owed** (locked §5.14) —
  abandonment escalates to governance (§8), never to a silent fallback.
- **Packaging per cell:** bridges ship inside the driver jar (isolated source
  set); partners resolve as the consumer's declared maven/Modrinth
  dependency; jar-in-jar only if a cell's toolchain requires it — decided per
  cell by the driver files (sub-18/sub-19), recorded in the driver README.
- **Absence-degradation UX:** every bridge maps to a
  `VineEngine.supports(...)` probe key; probes are enumerable so consumers
  can discover degradation; TCK asserts probe answers match bridge presence.
- **EMI JEI-compat ambiguity:** double-registration risk — mitigated by fixed
  probe order + register-once (§2).

## 5. Verification

TCK scenarios owned: `bridge_absence_boots_clean` (all cells, plus the
loaded-class scan asserting isolation), `accessory_fallback_roundtrip`,
`energy_interop`, `recipe_viewer_listing` (scripted dev-runtime check per
cell until automatable), `extension_point_freeze`. Each green on ≥2 drivers,
one per loader family (§8). Performance budget: probes run at boot only;
inactive bridges add zero per-tick cost.

## 6. Agent guidance

- **Conventions:** bridges live in
  `dev.vineengine.vine.internal.bridge.<partner>` in each driver's isolated
  source set; shared modules Java 21; 26.x drivers Java 25. Consumer-facing
  API never names a partner.
- **Comment policy:** adapter javadoc names the exact partner version range
  it absorbs and why; probe classes state the two-class rule invariant.
- **Forbidden:** importing partner types outside adapter classes;
  version-string parsing (partner or MC); hard-depending on any soft partner;
  global patches for interop (native-strategy data or nothing).
- **Done means:** stage checkboxes ticked, acceptance green on all four
  cells, status `done`, dashboard row in `docs/README.md` updated. Continuous
  subsystem: new partners append bridge rows without reopening done stages.
