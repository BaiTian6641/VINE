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

### Stage B — delivery (landed) and Stage C status

- [x] **Playback runtime:** one cutscene at a time, explicit viewers, a pure per-tick frame,
  a public frame listener, and a transport seam a cell installs.
- [x] **Scriptable client runs on both cells** (`vineTckClient`), which is what a director
  needs to *see* a cutscene: the client creates a world, runs engine commands, plays the
  cutscene through the testmod command and screenshots the result. Evidence:
  `vine-tck/fixtures/client/1.21.1-{fabric,neoforge}-cutscene.png`.
- [x] **Stage C — client application, 1.21.1-fabric (landed v1).** The Fabric cell installs the
  runtime's sender at bootstrap (`FabricCutsceneTransport`, wire id `vine:cutscene_frame`,
  versioned payload) and sends each frame to the one viewer it is addressed to; its client half
  (`VineCutsceneClient`) applies the camera yaw/pitch, the title lines through vanilla's
  title/subtitle API, and the frame's sounds as positioned client sounds at the camera position.
  An ending is a message, not a silence: the transport sends an end marker when the runtime stops
  playing, and the client then returns the camera to where it was pointing and clears the title
  (a staleness fallback covers a transport that dies mid-cutscene). Evidence: the scripted run
  `vine-tck/client/1.21.1-fabric.json` screenshots the cinematic mid-shot
  (`run/screenshots/1.21.1-fabric-cutscene.png` — title visible, view at the frame's yaw).
- [x] **Stage C — client application, 1.21.1-neoforge (landed v1).** The NeoForge cell mirrors
  the same design with only loader differences: the sender is installed at bootstrap and the
  payload (`NeoForgeCutsceneTransport`, the same `vine:cutscene_frame` wire id and v1 layout, a
  per-cell copy like the part-delta envelope) is registered through
  `RegisterPayloadHandlersEvent`, which on NeoForge carries the client handler too (no separate
  client-side receiver registration). Its client half (`VineCutsceneClient`) applies the camera
  yaw/pitch, the titles through `Gui`'s title/subtitle API, and the sounds as positioned sounds
  at the frame's camera position; the end marker (sent from this cell's server tick when the
  runtime stops playing) restores the camera and clears the title, with the same staleness
  fallback. The scripted run advances the cutscene's clock once per client tick — the runtime is
  pure and this cell drives it from no server tick — so a script can screenshot mid-cinematic.
  Evidence: `vine-tck/client/1.21.1-neoforge.json` screenshots the cinematic mid-shot
  (`run/screenshots/1.21.1-neoforge-cutscene.png` — title visible, view at the frame's yaw).
- [ ] **Stage C — remaining gaps (stated, not hidden):** `actors()` is not applied (the cell has no
  engine entity model — engine entity types still register an empty renderer); the frame's `fov` is
  carried but not applied (1.21.1 computes FOV inside `GameRenderer` with no per-frame hook, and v1
  adds no Mixin); the camera's authored *position* is not applied either, because moving the camera
  body without a Mixin means moving the player, which v1 rules out; an audio track's authored
  `volume` stops at the engine seam (`CutsceneFrame.sounds()` carries ids only).

## 4. Problems & blockers

- **Camera authority vs vanilla.** Taking over a player's camera is exactly the kind of thing a loader or another mod may also want to do. v1 keeps it *presentational*: the server sends a frame, the client applies it only while the cutscene it was told about is still playing (the end marker or the staleness fallback releases it), and a server-side `stop` ends it for everyone. No Mixin is used for the camera in v1; if one is needed, it stays in the driver jar per §5.9. A *client-side* input cancel is not implemented: it would need a C2S path of its own, and the yaw/pitch are re-applied every frame the server sends, so input during a cutscene is overridden rather than able to end it.
- **Time base.** Cutscene ticks are server ticks. A client that lags does not slow the cinematic down; it sees fewer frames. This is the same trade the rest of the engine makes (strict server authority, no rewind), stated here because cinematics are where it is most visible.
- **Actor selection.** v1 addresses actors by `VineId` (an engine actor reference is not stable across a reload); a cutscene that must name *specific* entities needs the sub-15/session identity surface, which is a later decision rather than a guess.

## 5. Verification

Owned TCK scenarios: cutscene frame golden (headless), playback + viewer set (live), and the client screenshot pass. Fixtures live with the other goldens under `vine-tck/fixtures/animation/`.
