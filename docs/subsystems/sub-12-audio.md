# SUB-12 — Audio

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M3 · **Depends on:** SUB-02, SUB-05, SUB-10 · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.17, §5.2 · **Module(s):** vine-api, vine-client-api, vine-core, vine-spi, drivers

## 1. Purpose

Version-free audio surface: sound events registered as structural descriptors
(the sub-02 content pipeline cooks per-cell `sounds.json`), positional /
entity-attached / UI playback with attenuation, and **dynamic music channels**
— area ↔ activity theme switching with crossfades, combat-state driven.
Non-goals: a DSP/sound-font layer, voice chat, replacing vanilla music when no
consumer music is active (Minimal Footprint).

## 2. Design

**Registration is structural** (§5.2): `SoundEventDescriptor`s materialize
into static `SoundEvent` registries at bootstrap. Per-cell `sounds.json`
(variants, weights, `stream` flags, subtitle keys) is **cooked** by the sub-02
content pipeline from consumer source audio — OGG Vorbis only; the vanilla
sound engine decodes nothing else (§4).

**API surface** (`vine-api`, `dev.vineengine.vine.audio`):

```java
public record SoundEventDescriptor(VineId id,
    List<SoundVariant> variants,                 // ogg path + weight + stream override
    SoundCategory category,                      // engine enum -> vanilla category
    @Nullable String subtitleKey,
    float defaultVolume, float defaultPitch,
    int attenuationDistance,                     // 0 = vanilla default (16)
    boolean stream) {}                           // streaming vs precache (§4)
public record SoundVariant(VineId oggFile, int weight, boolean streamOverride) {}
public enum SoundCategory { MASTER, MUSIC, RECORDS, WEATHER, BLOCKS, HOSTILE,
    NEUTRAL, PLAYERS, AMBIENT, VOICE }   // the real vanilla SoundSource set (no
                                         // UI value) — UI-class sounds ride MASTER
```

**Playback surface** (`vine-client-api`, `dev.vineengine.vine.client.audio`):

```java
public interface VineAudio {
  void playAt(VineId sound, VineWorldPos pos, float volume, float pitch);
  void playAttached(VineId sound, VineEntityRef entity, AttachMode mode); // TICK_FOLLOW | ONE_SHOT_AT
  void playUi(VineId sound, float volume, float pitch);
  MusicChannel music(VineId channelId);
}
public interface MusicChannel {                  // client-side per-channel state machine
  void pushTheme(VineId themeId, Crossfade fade);
  void setCombatThemes(VineId calm, VineId combat);  // driven by sub-10 combat state
  void clear(Crossfade fade);
}
public record Crossfade(int fadeOutTicks, int fadeInTicks, Curve curve) {}
```

Server side (`vine-api`): `VineAudioServer.setMusic(VinePlayer player, VineId
channel, VineId theme, Crossfade)` sends a trigger payload over sub-05
channels — server→client music is a *request*; the client owns mixing.

**Internals** (`vine-core`, `internal.audio`): descriptor codecs; music
channel registry + state machine (idle → fade-in → playing → fade-out);
combat-theme subscription to sub-10 pipeline events; category→vanilla mapping
table. Subtitles ship via cooked `sounds.json` + lang entries — the engine
never synthesizes them.

**Driver contract** (`vine-spi`, `internal.spi.AudioDriver`):

```java
void registerSoundEvent(SoundEventDescriptor d);          // structural phase
void playPositional(VineId sound, double x, double y, double z,
                    float volume, float pitch, int attenuation);
void playAttached(VineId sound, long entityUuid, AttachMode mode);
MusicBackend musicBackend();                              // channel mixing + vanilla suppression
```

- **MusicManager replacement:** drivers install a tick-based music selector
  (accessor/Mixin into `MusicManager`, quarantined §5.9) that consults the
  engine channel stack **first** and falls through to vanilla logic only when
  no consumer theme is active — conditional suppression, never global
  (Minimal Footprint). 1.21.1: Mixin on the tick method; 26.x: prefer
  accessor/interface patterns (unobfuscated runtime reduces Mixin need).
- **Init timing:** `SoundEngine` is lazy/late-init on all cells; registration
  must finish before resource load, playback pre-init logs once and no-ops.
- **Entity-attached:** driver wraps a ticking follow-sound instance per cell;
  `ONE_SHOT_AT` pins the spawn position.
- **26.x drift:** probe `VineEngine.supports("audio.sound-engine-v2")` between
  drops — never version strings.

**Data flow:** descriptor (Java/JSON) → structural registry → cooked
`sounds.json` → playback translated to native sound instances. Music: server
trigger (sub-05 payload) or client area/activity hooks → channel state
machine → crossfade → `MusicBackend` (streaming variants); dimension change
re-evaluates the active theme (§4).

## 3. Stages

### Stage A — Sound-event descriptors & cooked sounds.json

- [ ] **Do:** `SoundEventDescriptor`/`SoundVariant`/`SoundCategory` + codecs;
  structural registration via sub-02; pipeline cook emitting per-cell
  `sounds.json` (variants, weights, stream, subtitle keys).
- **Acceptance:** testmod sound event in native registries on all four cells;
  cooked `sounds.json` byte-matches golden fixtures per cell.
- **Touches:** vine-api `audio`, vine-core `internal.audio`, vine-spi,
  drivers/*, vine-testmod.
- **Bootstrap prompt:**
  > Implement SUB-12 Stage A per `docs/subsystems/sub-12-audio.md` §2 (read
  > `vine-engine-plan.md` §5.17/§5.2 + `docs/TEMPLATE.md` first; Prime
  > Invariant, Java 21 bytecode). Structural sound-event registration via
  > sub-02 + per-cell `sounds.json` cooking. Acceptance: registry presence on
  > all cells + golden `sounds.json` fixture match.

### Stage B — Playback surface

- [ ] **Do:** `VineAudio` positional/UI playback with attenuation mapping;
  `playAttached` tick-follow; safe pre-init no-op.
- **Acceptance:** TCK `audio-event-smoke` (§5): positional + attached + UI
  plays on one NeoForge + one Fabric cell; no early-init crash; attenuation
  distance honored (probe at 2× distance).
- **Touches:** vine-client-api, vine-spi, drivers/* (client), vine-testmod.
- **Bootstrap prompt:**
  > Implement SUB-12 Stage B per `docs/subsystems/sub-12-audio.md`: `VineAudio`
  > playback (positional/attenuated, entity tick-follow, UI) in vine-client-api
  > + drivers; playback before sound-engine init logs once and no-ops.
  > Acceptance: TCK `audio-event-smoke` green on one cell per loader family.

### Stage C — Categories, volumes, subtitles

- [ ] **Do:** `SoundCategory`→vanilla mapping per cell; descriptor
  volume/pitch defaults applied at playback; subtitle keys into cooked
  `sounds.json` + lang.
- **Acceptance:** client per-category volume sliders affect testmod sounds in
  3 categories on both loader families; subtitle renders when enabled.
- **Touches:** vine-core, drivers/* (client), vine-testmod.
- **Bootstrap prompt:**
  > Implement SUB-12 Stage C per `docs/subsystems/sub-12-audio.md`: category
  > mapping, default volume/pitch, subtitle data end-to-end (descriptor →
  > cooked sounds.json → rendered subtitle). Acceptance: volume sliders +
  > subtitle rendering verified on both loader families.

### Stage D — Dynamic music channels (client runtime)

- [ ] **Do:** `MusicChannel`/`Crossfade` state machine; per-driver
  `MusicBackend` with conditional vanilla `MusicManager` suppression; combat
  themes wired to sub-10 combat-state events.
- **Acceptance:** theme push suppresses vanilla music and plays (streaming);
  `clear` crossfades back to vanilla; combat flip switches calm↔combat;
  vanilla timing untouched while idle. One cell per loader family.
- **Touches:** vine-client-api, vine-core, vine-spi, drivers/* (client).
- **Bootstrap prompt:**
  > Implement SUB-12 Stage D per `docs/subsystems/sub-12-audio.md`: music
  > channel state machine + `MusicBackend` with tick-based MusicManager
  > replacement falling through to vanilla when no consumer theme is active
  > (Minimal Footprint; Mixin quarantine §5.9). Combat themes subscribe to
  > sub-10 events. Acceptance: push/clear + combat switch on one NF + one
  > Fabric cell.

### Stage E — Server→client triggers & dimension sync

- [ ] **Do:** `VineAudioServer.setMusic` payload over sub-05 channels;
  dimension-change re-evaluation via the sub-01 player hook. Requires sub-05.
- **Acceptance:** trigger reaches only the addressed client; theme correctly
  restarts or switches across a dimension change, one cell per loader family.
- **Touches:** vine-api, vine-core, vine-spi, drivers/*.
- **Bootstrap prompt:**
  > Implement SUB-12 Stage E per `docs/subsystems/sub-12-audio.md`:
  > server→client music trigger payloads via sub-05 (codec DSL, engine
  > channels) + dimension-change re-evaluation of the active theme.
  > Acceptance: targeted delivery + dimension-change behavior on one cell per
  > loader family.

## 4. Problems & blockers

- **Vanilla music fights custom music.** Vanilla `MusicManager` self-selects
  tracks each tick. Mitigation: suppression hook consults the engine channel
  stack and activates **only while a consumer theme is active**; fallthrough
  preserves vanilla timing exactly. Owner: here.
- **Music sync on dimension change.** Vanilla stops/reselects music across
  dimensions. Decision: engine re-evaluates on the sub-01 dimension-change
  hook and reapplies the top theme with a short crossfade; server-scoped
  themes are resent by the rules object that set them. Owner: here.
- **OGG-only.** Vanilla decodes OGG Vorbis exclusively; the pipeline must
  reject/transcode other formats at cook time, not runtime. Owner: sub-02
  pipeline, enforced by Stage A fixtures.
- **Streaming vs precache.** Long music must stream (`stream: true`,
  file-loaded) or the whole file is decoded into memory (vanilla streams or
  precaches in full — it never truncates); short SFX precaches.
  `SoundVariant.streamOverride` wins over the descriptor default. Owner: here.
- **Trigger payload drift.** Payloads ride sub-05 engine channels with
  engine-managed versioning (§5.6); no cell-specific handling.

## 5. Verification

- TCK `audio-event-smoke` (owned here, plan §7): descriptor registered +
  cooked `sounds.json` valid + positional/attached/UI playback exercised
  (headless log assertions) on all four cells. Ship gate: ≥2 drivers, one per
  loader family (§8).
- Golden fixtures: cooked `sounds.json` per cell byte-compared; non-OGG source
  rejected at cook time.
- Client smoke: audible attenuation, entity-follow, per-category sliders,
  subtitle rendering, theme push with crossfade, combat switch, vanilla music
  resumption after `clear`.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.audio` (public) /
  `dev.vineengine.vine.client.audio` (client) /
  `dev.vineengine.vine.internal.audio` + `internal.spi`; shared modules Java
  21. Descriptors are data (Java + JSON paths); audio files are source assets
  cooked per cell.
- **Comment policy:** javadoc states why/invariants (conditional suppression;
  pre-init no-op by design); driver comments name the per-cell MusicManager /
  sound-engine difference absorbed.
- **Forbidden:** version-string parsing (probes §5.12); `net.minecraft.*`/
  loader types in API signatures; Mixins outside drivers; touching vanilla
  audio behavior with no consumer content registered (Minimal Footprint §5.1).
- **Done means:** all boxes ticked, acceptance green on all cells, status
  `done`, dashboard row in `docs/README.md` updated.
