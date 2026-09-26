# SUB-23 — Cutscenes

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M3 (client application: M2 surface) · **Depends on:** SUB-01, SUB-05, SUB-09, SUB-14 · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.11 (§5.14, §5.21) · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

Server-driven cinematics for RPG-shaped content: a cutscene is authored as data
(timeline of keyframed tracks), played by the server for every player who should see it,
and *evaluated deterministically* so the same tick produces the same frame on every cell.
A cutscene is not a client effect a mod fires and hopes about — it is content with a
clock, and the clock belongs to the server.

Non-goals: a timeline editor (authoring is JSON), pathfinding cameras that react to the
world mid-shot (the camera follows the authored track), and dialogue systems (sub-15's
quest hooks carry those). Rendering lives in sub-16/17; this subsystem owns *what* a frame
contains, never how a cell draws it.

## 2. Design

- **API** (`vine-api`, `dev.vineengine.vine.cutscene`):
  - `CutsceneDescriptor(VineId id, int lengthTicks, List<Track> tracks, boolean skippable)`
  - `Track` is sealed: `Camera(List<CameraKey>)`, `Actor(VineId actor, VineId animationId, long startTick, boolean loop)`, `Audio(VineId sound, long tick, float volume)`, `Title(String text, long startTick, long endTick, VineId style)`.
  - `CameraKey(long tick, Vec3 position, float yaw, float pitch, float fov, Easing easing)` — interpolation is per-segment, with the easing named by the *earlier* key.
  - `CutsceneFrame(long tick, CameraShot camera, List<ActorShot> actors, List<VineId> sounds, List<String> titles)` — one tick's worth of everything a client needs, produced by the engine.
  - `VineCutscenes.play(VineId cutscene, Set<UUID> viewers)`, `.stop()`, `.tick()`, `.frame()`, `.playing()`.
- **Internals** (`vine-core`): `CutsceneRuntime` — one active cutscene at a time (v1), evaluated purely: `frame(tick)` is a function of the descriptor and the tick, with no world reads. That is what makes a cutscene golden-testable on a plain JVM *and* identical for every viewer: everyone is sent the same frame, and a late joiner is sent the frame for the current tick rather than a private timeline.
- **Player participation:** viewers are explicit. A cutscene does not hijack every player in the world; the server tells the chosen viewers, and each client applies the frame locally (§5.11's "server owns state, client renders" rule).
- **Interaction with other subsystems:** a quest completion (sub-15) is the canonical trigger; `VineQuests.addCompletionListener` is the hook. While a cutscene is playing the engine does not freeze the world — content that wants a static shot authors actors that do not move.
- **Driver contract** (`vine-spi`): a frame is delivered over a sub-05 channel; the client half applies camera/actor/sound/title presentation. Absent a client capability, the frame is simply not drawn (Minimal Footprint: nothing installs on a cell that cannot render).

## 3. Stages

### Stage A — Descriptor, parser, deterministic evaluator

- [ ] **Do:** records + codecs; JSON parsing through the engine's authoring path; pure `frame(tick)` evaluation.
- **Acceptance:** headless golden fixture: every sample tick's camera/actor/audio/title values byte-identical across runs, on a classpath with no client jar.
- **Touches:** vine-api `cutscene`, vine-core `internal.cutscene`, vine-tck fixture runner.

### Stage B — Playback runtime + delivery

- [ ] **Do:** `CutsceneRuntime` (play/stop/tick), viewer set, per-tick frame production, channel delivery.
- **Acceptance:** TCK scenario: a played cutscene produces the expected frame sequence; a second viewer sees the same frames; stopping ends it for everyone.
- **Touches:** vine-core `internal.cutscene`, vine-spi channel, driver transports.

### Stage C — Client application

- [ ] **Do:** camera control, actor animation application, sound playback, title overlay on each cell; screenshots as artifacts.
- **Acceptance:** a scripted client run shows a cutscene frame (screenshot committed) and returns control afterwards.
- **Touches:** drivers (client halves), sub-16/17 surfaces.

## 4. Problems & blockers

- **Camera authority vs vanilla.** Taking over a player's camera is exactly the kind of thing a loader or another mod may also want to do. v1 keeps it *presentational*: the server sends a frame, the client applies it only while the cutscene it was told about is still playing, and any player input or a `stop` cancels it. No Mixin is used for the camera in v1; if one is needed, it stays in the driver jar per §5.9.
- **Time base.** Cutscene ticks are server ticks. A client that lags does not slow the cinematic down; it sees fewer frames. This is the same trade the rest of the engine makes (strict server authority, no rewind), stated here because cinematics are where it is most visible.
- **Actor selection.** v1 addresses actors by `VineId` (an engine actor reference is not stable across a reload); a cutscene that must name *specific* entities needs the sub-15/session identity surface, which is a later decision rather than a guess.

## 5. Verification

Owned TCK scenarios: cutscene frame golden (headless), playback + viewer set (live), and the client screenshot pass. Fixtures live with the other goldens under `vine-tck/fixtures/animation/`.
