# SUB-09 — Animation runtime

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M2 (data + evaluator) → M5 (render polish) · **Depends on:** SUB-08 · **Blocks:** SUB-10, SUB-17
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.14 (§5.20 partner policy) · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

One GeckoLib-format asset (Blockbench `geo.json` + `animation.json` — format
adoption locked 2026-09-23) feeds two consumers: (a) a headless server-side
evaluator in `vine-core` producing attack windows and bone-attached OBB
colliders for sub-08's multipart hitboxes, and (b) a client render backend
(GeckoLib-the-mod, hard dependency where it ships) behind an SPI. Animation is
*data driving combat timing*, not just visuals. Non-goals: designing a
competing asset format; a VINE-native fallback renderer (not owed, §5.14);
camera/FX/post render surfaces (sub-17); GeckoLib-version drift anywhere
outside drivers.

## 2. Design

- **Canonical evaluation:** timelines are expressed in ticks; the canonical
  pose of an animation at tick `t` is defined by the server evaluator at 20
  TPS. Client render interpolation between canonical poses is presentational
  only and MUST NOT feed gameplay. Golden fixtures compare canonical-tick
  poses only.
- **API surface** (`vine-api`):
  ```java
  public record AnimationDescriptor(VineId id, VineId geometry,
      List<Track> tracks, List<AttackWindow> windows) {
      public static Codec<AnimationDescriptor> codec(); }
  public record AttackWindow(VineId anim, int startupTicks, int activeTicks,
      int recoveryTicks, int comboCancelTicks) {}
  public interface AnimationController {          // server-side handle per entity
      void play(VineId anim, Transition transition);
      boolean isInWindow(WindowKind kind);        // STARTUP/ACTIVE/RECOVERY/COMBO_CANCEL
      Obb boneCollider(VineId bone);              // feeds sub-08 parts
  }
  ```
  Registered as design descriptors via sub-02 dynamic registries (Java + JSON).
- **Internals** (`vine-core`, headless): GeckoLib parser → canonical model
  (bones, pivots, tracks); keyframe interpolation (linear / smooth / catmull-
  rom per format easing); attack-window derivation; bone-attached OBB
  computation. Deterministic: pinned `double` math order, no wall-clock, no
  client classes — runs in the TCK harness without LWJGL.
- **Driver contract** (`vine-spi` + per-cell): `VineAnimationBackend` is SPI,
  never API — `bind(entityHandle, VineId geometry)`, `play(VineId anim,
  int startTick)`. GL4 on both 1.21.1 cells; GL5 on both 26.x cells; each
  driver maps the SPI onto its GeckoLib generation and comments the exact
  drift absorbed. Consumers only ever see VINE animation IDs.
- **Data flow / sync:** server selects/approves the animation; a sub-05
  packet `(entityId, animId, startTick)` drives clients; controllers are
  server-authoritative for windows (sub-10 reads them). `@VineUnsafe`
  donation path: consumer 3D prototypes may render per-version behind
  `@VineUnsafe` and donate working code into the M5 backend stages.
- **Asset pipeline:** Blockbench export → `assets/<ns>/vine/geo/<path>.geo.json`
  + `assets/<ns>/vine/animations/<path>.animation.json`, identical resource
  layout on every cell; the descriptor JSON sits beside datapack registries
  (sub-02) and references both assets by `VineId`.

## 3. Stages

### Stage A — GeckoLib parser + canonical model

- [ ] **Do:** parse `geo.json`/`animation.json` into the `vine-core`
  canonical model (bones, pivots, tracks, easings); validation errors with
  asset coordinates.
- **Acceptance:** round-trip unit check on two reference assets; parser runs
  in a plain JVM (no Minecraft, no client).
- **Touches:** `dev.vineengine.vine.internal.animation.model`.
- **Landed (2026-09-26, single-document form; the split form is the named remainder):**
  `vine-core/internal/animation/AnimationAssetParser` parses a Blockbench document —
  `minecraft:geometry[0].bones` (name/parent/pivot/rotation; cubes/uv ignored for now)
  plus the `animations` map (loop, `animation_length`, per-bone `position`/`rotation`/
  `scale` channels keyed by string times, values as arrays or `{post|pre, …}` objects)
  plus markers from `sound_effects`/`particle_effects`. It is strict where an authoring
  mistake lives — dangling parent, cyclic chain, duplicate keyframe time, non-finite or
  non-three-number values, an animated bone the geometry does not declare, a marker
  outside the clip, an active window that ends before it starts — each failing with a
  message naming asset, clip, bone and time. Determinism comes from sorted keys and
  rejected duplicates, not from hoping a map iterates the same way twice.
  **Remainder, named:** real consumer assets ship as the ecosystem's *two-file* pair
  (`geo.json` + `animation.json`), while this parser takes one merged document (the
  fixture's form). Stage D/E's loader must concatenate the pair, or the parser needs an
  entry point taking both; the doc's Stage-A line describes that split form, so it stays
  open until it exists.
- **Bootstrap prompt:**
  > Implement SUB-09 Stage A per `docs/subsystems/sub-09-animation.md` (read
  > plan §5.14 + `docs/README.md` conventions). Parser + canonical model in
  > `vine-core`, Java 21, zero Minecraft/client deps. Acceptance: both
  > reference assets parse and re-emit identical canonical structures.

### Stage B — server evaluator + golden timing TCK

- [ ] **Do:** timeline evaluation at 20 TPS, keyframe interpolation, attack
  windows (startup/active/recovery/combo-cancel), bone-attached OBB output.
- **Acceptance:** golden timing fixtures (`swing_basic`: window edges at exact
  ticks; OBB pose table per canonical tick) pass on 1.21.1-NF +
  1.21.1-Fabric, headless.
- **Touches:** `dev.vineengine.vine.animation`, `internal.animation.eval`, TCK.
- **Landed (2026-09-26):** the evaluator is engine-side, headless and pure:
  `PoseEvaluator` composes each bone's transform down its parent chain (parent-first in
  sorted-name order, memoised, cycle-guarded), interpolates each channel segment with
  the easing of the keyframe the segment *starts* at, wraps looping clips and clamps
  one-shot clips, derives `TimingWindows` from VINE's marker namespace
  (`vine:active_start`/`active_end`/`cancel_start`, 20 TPS), and builds a part's
  world-space `OrientedBox` from its bone, the actor's position and yaw. The API side is
  `dev.vineengine.vine.animation` (`AnimationAsset`, `SkeletonPose`, `Quaternion`,
  `TimingWindows`, `OrientedBox`) behind `VineAnimations` + `AnimationBackend`, the
  sixth instance of the bind-free backend pattern.
  **Evidence:** the fixture asset (`wyvern_stub.json`, 5 bones, an idle and a strike
  clip with VINE markers) is evaluated twice over — once by a plain-JVM harness
  (`:vine-tck:evaluatorFixtures`, whose 16-entry classpath is printed on every run and
  contains no driver, testmod, Minecraft or LWJGL jar, with three `Class.forName`
  probes returning `ClassNotFoundException`) comparing byte-for-byte against committed
  goldens (`wyvern_stub.poses.txt` sha256 `a24192aa…`, `wyvern_stub.windows.txt` sha256
  `15d47baf…`), and once on each cell through the engine facade
  (`vine_test tck_anim_probe`, digests `923ef907…` and `f686d6a3…`, windows
  `startup=6 active=5 recovery=9 cancel=15 total=20` for the strike and a
  recovery-only window for the idle) — with the `animation_pose` scenario green on
  both 1.21.1 cells at **39/39 scenarios each**, alongside `build`, the three purity
  gates, `verifyDatagen`, `crossCellRoundTrip` and both GameTest batches. Two deliberate demonstrations accompany it: the
  golden check fails loudly on a one-character easing change (13 differing lines, then
  reverted), and the loop wrap is pinned as an exact value
  (`requested=2.4 → effective=0.3999999999999999`), because that is where a
  "close enough" evaluator would drift from gameplay.
  **Three decisions worth carrying forward:** the easing of a segment is the *starting*
  keyframe's (documented on both the class and the sampler, because a client backend
  reading the opposite convention would draw a different pose than gameplay acts on);
  `Quaternion.then`'s javadoc now states that its argument is applied first (the
  implementation was right and the sentence was backwards — a backend following the old
  wording would have composed every chain incorrectly); and the goldens are regenerated
  only with `-Pvine.tck.writeFixtures=true`, so a deliberate evaluator change commits
  the regenerated fixtures in the same change.
- **Bootstrap prompt:**
  > Implement SUB-09 Stage B per the file. Deterministic evaluator: pinned
  > math order, no wall-clock. TCK fixtures assert window-edge ticks and OBB
  > tables byte-exactly on both 1.21.1 cells without a client.

### Stage C — registry descriptors + play/approval sync

- [ ] **Do:** `AnimationDescriptor` on sub-02 registries (Java + JSON,
  hot-reload); `AnimationController`; sub-05 play packet + server approval.
- **Acceptance:** TCK sync scenario: server `play()` observed by client-side
  recorder within 1 tick on both 1.21.1 cells; rejected plays never render.
- **Touches:** `vine.animation`, `internal.animation.sync`, sub-05.
- **Bootstrap prompt:**
  > Implement SUB-09 Stage C per the file. Descriptors via sub-02; sync via
  > sub-05 `(entityId, animId, startTick)`. Server authoritative. Acceptance:
  > sync TCK green on both 1.21.1 cells.

### Stage D — render backend GL4 (1.21.1 cells)

- [ ] **Do:** `VineAnimationBackend` SPI; GL4-backed implementations for
  1.21.1-NF + 1.21.1-Fabric with GeckoLib as a hard dependency; client smoke
  renders the wyvern (sub-08 fixture).
- **Acceptance:** client smoke checklist: correct model/animation by VINE ID
  on both 1.21.1 cells; server window state unaffected by render.
- **Touches:** `internal.animation.spi`, both 1.21.1 drivers.
- **Bootstrap prompt:**
  > Implement SUB-09 Stage D per the file. Backend is SPI, never API;
  > consumers see only VINE IDs. Quarantine every GL4-specific call in the
  > driver with a comment naming it. Acceptance: wyvern smoke render on both
  > 1.21.1 cells.

### Stage E — render backend GL5 (26.x cells) — M4/M5 window

- [ ] **Do:** GL5-backed implementations for 26.x-NF + 26.x-Fabric behind the
  same SPI; no shared GL-version code above the SPI.
- **Acceptance:** same client smoke checklist green on both 26.x cells;
  consumer code unchanged from Stage D.
- **Touches:** both 26.x drivers (Java 25 permitted).
- **Bootstrap prompt:**
  > Implement SUB-09 Stage E per the file. Map `VineAnimationBackend` onto
  > GL5 in the 26.x drivers only; drift from GL4 is documented per call site.
  > Acceptance: identical consumer smoke scenario passes on 26.x cells.

### Stage F — @VineUnsafe donation path + M5 polish handoff

- [ ] **Do:** `@VineUnsafe`-annotated per-version prototype hooks for consumer
  3D experiments; documented donation contract (what shape donated code must
  have to enter the backend); handoff notes to sub-17 for M5 render polish.
- **Acceptance:** donation-path sample compiles behind `supports()` probes on
  two cells; contract doc reviewed against §5.11 phasing.
- **Touches:** `vine.animation` (annotations), drivers, sub-17 handoff note.
- **Bootstrap prompt:**
  > Implement SUB-09 Stage F per the file. Prototypes stay per-version behind
  > `@VineUnsafe`; the donation contract defines the SPI-conformant shape for
  > M5. Acceptance: sample prototype builds on one 1.21.1 cell + one 26.x
  > cell.

## 4. Problems & blockers

- **GeckoLib cannot supply server-side bone positions (verified 2026-09-26,
  GeckoLib 5 wiki):** bone world positions are exposed through the render pass
  (`RenderPassInfo`) and animation controllers are invoked per render frame, so
  neither is usable as an authoritative hitbox source on a dedicated server. This
  is *why* the headless evaluator exists rather than being a nicety: per-bone
  hitboxes (sub-08 Stage D), attack windows and sweep shapes (sub-10 Stage C) all
  consume it. **It is therefore the critical path for the RPG/Monster-Hunter
  target** — nothing downstream of it can be bone-accurate until it lands, and the
  server-tick time base (never render frames or client animation time) is the
  contract that keeps it authoritative.

- **Evaluator fidelity vs client interpolation** — decided: canonical
  evaluation at 20 TPS tick boundaries is the single source of truth; client
  interpolation is cosmetic and never read back into gameplay. Golden
  fixtures pin canonical ticks only.
- **GL4↔GL5 API split containment** — the SPI is generation-neutral; each
  driver owns its mapping; any type that cannot be expressed generation-
  neutrally stays out of the SPI (escalate to sub-01 SPI owners via hub).
- **Headless TCK** — the evaluator must not touch LWJGL/client classes;
  enforced by running Stage B fixtures in a plain JVM module.
- **Asset pipeline variance** — Blockbench exports (degrees, pivot spaces,
  easing names) differ by plugin version; mitigation: parser normalizes to
  the canonical model at load, validation errors cite asset path + bone.
- **GeckoLib abandonment / hard-dep risk** — accepted per locked policy
  (§5.20); a future backend enters behind the same SPI, no API change.
  26.x availability VERIFIED (was unverifiable at first draft): GeckoLib
  5.5.x ships for 26.1.2 and 26.2 on Fabric AND NeoForge (CurseForge,
  Sep 2026), so Stage E's GL5 backend has a real dependency target.
  Re-verified 2026-09-25 against the publisher's own version table
  (modrinth.com/mod/geckolib, wiki.geckolib.com/docs/geckolib5): the line is
  26.1 → 5.5, 26.1.2 → 5.5.2, 26.2 → 5.5.6, Fabric and NeoForge both listed.
  **One fact the adapters must carry:** GL5 changed its Java package namespace
  from `software.bernie.geckolib` to `com.geckolib`, so `GeckoLib5Adapter`
  probes the new namespace only — a GL4-style probe against a GL5 jar finds
  nothing and would silently report the partner absent.

## 5. Verification

- Golden timing fixtures (window edges + OBB tables), headless, every cell.
- Sync TCK (Stage C): server-approved play observed client-side.
- Client smoke checklist (Stages D/E): wyvern renders by VINE ID per cell.
- Every surface ships only with its TCK scenario passing on ≥2 drivers, one
  per loader family (§8).

## 6. Agent guidance

- **Conventions:** per `docs/TEMPLATE.md` §6 — package roots
  `dev.vineengine.vine.animation` (public) / `dev.vineengine.vine.internal.animation.*`
  (SPI/drivers); Java 21 bytecode shared (the evaluator MUST stay Java 21 and
  client-free), Java 25 in 26.x drivers. Descriptors are data (Java + JSON).
- **Forbidden (this file's emphasis):** GeckoLib types in API signatures or
  SPI; render interpolation feeding gameplay; client-class references from
  `vine-core`; a second asset format.
- **Done means:** per template — all stages ticked, acceptance green on all
  covered cells, status `done`, dashboard row updated in `docs/README.md`.
