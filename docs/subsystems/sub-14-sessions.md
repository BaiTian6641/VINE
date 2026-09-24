# SUB-14 — Sessions & parties (`VineSession`)

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M1 basics → M3 full · **Depends on:** SUB-03, SUB-05 · **Blocks:** SUB-15
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.19 · **Module(s):** vine-api | vine-core | vine-spi | drivers

## 1. Purpose

The GameMode/GameState pattern ported to Minecraft (§5.19): a
**server-authoritative rules object** over **replicated session state**, so
consumers build structured activities (hunts, dungeon runs, arena matches;
sub-15 quests compose these) without re-solving authority, replication,
persistence, and late-join. Also owns the engine **party primitive** (FTB
Teams pattern, engine-owned — identical behavior with nothing else
installed). Source of truth for `VineSession`. Non-goals: client presentation
(sub-16), quest content (sub-15), matchmaking, cross-server sessions.

## 2. Design

**API surface** (`vine-api`, `dev.vineengine.vine.session` / `.party`):

```java
enum SessionPhase { CREATED, ACTIVE, COMPLETED, ABANDONED }

/** Public session-scoped state; replicated to all participants. */
record SessionState(SessionPhase phase, long ticksRemaining,
                    VoxelData objectives, VoxelData sharedFlags) {}
/** Replicated to owner; public only when the session type opts in. */
record ParticipantState(UUID player, VoxelData contribution,
                        VoxelData loadout, VoxelData progress) {}

sealed interface SessionScope permits SessionScope.World, SessionScope.Party {
    enum Kind { WORLD, PARTY }                 // SessionTypeDescriptor.defaultScope
    Kind kind();
    record World(VineId worldId) implements SessionScope {}  // kind() = WORLD
    record Party(PartyRef party) implements SessionScope {}  // kind() = PARTY
}

/** Stable party handle shared across subsystem APIs (sub-15 keys progress by it). */
record PartyRef(VineId partyId) {}

/** Creator handle for one registered session type; `SessionManager.create` resolves it
    from the sub-02 descriptor, and consumers (e.g. sub-15 `linkSession`) hold it directly. */
interface SessionFactory {
    VineId sessionType();
    VineSession create(SessionScope scope, VoxelData params);
}

/** Server-only authority over one session. Never replicated. */
abstract class SessionRules {
    protected void onCreated(SessionContext ctx) {}
    protected abstract ActionVerdict validate(SessionAction action, SessionContext ctx);
    protected void onParticipantJoined(SessionContext ctx, UUID player) {} // re-join tolerant
    protected void onPhaseAdvanced(SessionContext ctx, SessionPhase next) {}
}

interface VineSession {
    VineId id();  VineId type();  SessionScope scope();  SessionState state();
    Optional<ParticipantState> participant(UUID player);
    Set<UUID> participants();
}
interface SessionManager {
    VineSession create(VineId sessionType, SessionScope scope, VoxelData params);
    Optional<VineSession> get(VineId sessionId);
}

// dev.vineengine.vine.party — engine-owned team store
enum PartyRank { MEMBER, OFFICER, OWNER }
interface VineParty {
    VineId id();  UUID owner();
    Map<UUID, PartyRank> members();   // members share progress
    Set<VineId> allies();             // allies never share progress
}
interface PartyManager {              // API-only creation mode
    VineParty createParty(UUID owner);
    Optional<VineParty> partyOf(UUID player);
    void join(VineId party, UUID player);  void leave(VineId party, UUID player);
}
```

Session types are sub-02 descriptors: `record SessionTypeDescriptor(VineId
id, SessionScope.Kind defaultScope, boolean participantStatePublic, VineId
rulesFactory)` + `Codec`, Java + JSON paths. Sub-01 bus events:
`SessionCreatedEvent`, `SessionPhaseChangedEvent`, `PartyJoinedEvent`,
`PartyLeftEvent`.

**Internals** (`vine-core`): server-side `SessionService` owns live sessions,
transitions only through `SessionRules`, persists per-world via `VoxelData`
(party store at overworld level). Replication controller sends diffs:
`SessionState` → all participants; `ParticipantState` → owner, or all when
`participantStatePublic`. Late-join and reconnect share one engine-owned
snapshot push over sub-05, delivered before the consumer's session content
ticks for that client.

**Driver contract** (`vine-spi`): `SessionPersistenceSpi` mount/flush on
world load/save (1.21.1: `SavedData`/`PersistentState` on the overworld;
26.x: same concept, unobfuscated names). Login/logout feed reconnect via
sub-01 normalized hooks. No per-cell API-shape difference.

## 3. Stages

### Stage A — Session model & server lifecycle (M1)

- [x] **Do:** `vine-api` session types per §2; in-memory `SessionService` in
  `vine-core`; transitions only via `SessionRules`; action→verdict round-trip.
- **Acceptance:** headless scenario: create, invalid action rejected, valid
  action advances phase; transition log lines.
- **Touches:** `vine-api` `…vine.session`; `vine-core` `…internal.session`.
- **Bootstrap prompt:**
  > Implement SUB-14 Stage A (read `docs/README.md` conventions + plan §5.19
  > first). Build the `dev.vineengine.vine.session` API exactly as sketched in
  > sub-14 §2 (Java 21, no `net.minecraft.*` in signatures) plus an in-memory
  > `SessionService` in `vine-core` transitioning sessions only through
  > `SessionRules`. Acceptance: headless create/reject/advance scenario with
  > log lines. No networking or persistence yet.

### Stage B — VoxelData persistence (M1)

- [x] **Do:** `SessionPersistenceSpi` in `vine-spi`; per-driver store mount on
  world save/load; sessions + participant states serialize via `VoxelData`;
  suspend-on-unload policy.
  - **Landed 2026-09-24:** `SessionPersistenceSpi` (load/save) +
    `DriverContext.mountSessionPersistence/flushSessionPersistence`;
    `SessionManager.snapshot()/restore()/flush()/liveSessions()`.
    `SessionService` serializes every live session (id, type, scope, phase,
    ticks, objectives, shared flags) into one `VoxelData` blob under schema
    `vine:session_store` — registered before the schema registry freezes (the
    first live failure: a lazy registration was refused post-freeze and every
    flush silently failed). Restore re-hydrates sessions in their persisted
    phase (suspend-on-unload: an ACTIVE session comes back ACTIVE), keeps the
    id counter ahead so ids are never reused, and skips sessions whose type
    factory is gone. Drivers mount a `FileSessionStore` (atomic temp+move) on
    first world load: NF flushes on every `LevelEvent.Save`, Fabric at
    `SERVER_STOPPING` (no per-save callback on that loader — documented seam).
  - **Evidence:** new `vine_test:session_persistence` TCK scenario — create →
    ACTIVE → score=12 → flush → `SaveReloadWorld` → restored: 11/11 scenarios
    PASS on both 1.21.1 cells (real graceful reboot, real file store). The
    cross-cell leg rides the existing golden-fixture session section
    (VoxelData session state written on NF, verified on Fabric).
  - **Assumptions:** Fabric's store path derives from the process working
    directory (`<cwd>/world/vine/sessions.vbl`); a renamed `level-name` needs
    the server-properties read (noted in the driver).
- **Acceptance:** golden-fixture round-trip of an ACTIVE session across
  save/load, readable on a second cell.
- **Touches:** `vine-spi`, `vine-core` store, all drivers' persistence attach.
- **Bootstrap prompt:**
  > Implement SUB-14 Stage B. Add `SessionPersistenceSpi` (mount/flush on
  > world load/save) to `vine-spi`; wire each driver through its cell's
  > saved-data mechanism; serialize via `VoxelData` (sub-03). Acceptance:
  > golden round-trip on ≥2 drivers, one per loader family.

### Stage C — Replication & late-join sync (M1→M2)

- [ ] **Do:** replication controller over sub-05 channels; scoping per §2;
  one snapshot path for late-join and reconnect.
- **Acceptance:** TCK `session_late_join`: joiner of an ACTIVE session gets a
  full snapshot before consumer content ticks; owner-only field invisible to
  a second participant.
- **Landed (snapshot half):** `VineSession.join(player, loadout)` adds or re-adds
  a participant (the first joiner owns the session; `isOwner` answers), calls the
  rules' join chord, and triggers the engine's snapshot for that player — join,
  late join and reconnect share exactly one path. `VineSession.replicationSnapshot(viewer)`
  encodes the payload a client receives (schema `vine:session_repl`): session
  id/type, phase, ticks, objectives, shared flags, plus the viewer's own
  participant state and — only when the type opts in through
  `SessionRules.publicParticipantState()` — every other participant's state. Owner
  -only is the default because private progress is the common case. The snapshot
  travels the engine-owned `vine:session` channel as a `session/snapshot` message
  (OPTIONAL policy, server-authoritative) and is logged with its size, and the
  engine resolves a participant through the `Players` UUID→`VinePlayer` seam the
  drivers install at server start.
- **Acceptance met (snapshot half):** TCK `session_late_join` — a joiner of an
  ACTIVE session decodes the payload it would receive: `phase=ACTIVE score=7
  flag=storm selfIncluded=1 othersIncluded=0` for the default type, and
  `othersIncluded=1` for the opted-in type, on both 1.21.1 cells; the send log
  shows the snapshot leaving for the joining participant.
  **Remaining:** change-driven re-sends (a transition or a state write currently
  does not push a fresh snapshot to participants), and real client receipt,
  which needs a client runner no stage provides yet — the payload is the same
  bytes this scenario decodes.
- **Touches:** `vine-core` replication, sub-05 channel, driver login hooks.
- **Bootstrap prompt:**
  > Implement SUB-14 Stage C over the sub-05 channel API: replicate per the §2
  > scoping rules; late-join/reconnect via one engine-owned snapshot.
  > Acceptance: TCK `session_late_join` on one NeoForge + one Fabric cell,
  > including the visibility check.

### Stage D — Party primitive (M3)

- [ ] **Do:** `…vine.party` API; engine-owned `VoxelData` party store
  (overworld level); ranks, allies, API-only creation; join/leave events;
  member-only progress sharing in session accounting.
- **Acceptance:** TCK `party_progress_isolation`: two members accrue shared
  progress; allied party unchanged; leaving stops accrual.
- **Touches:** `vine-api` party package, `vine-core` store + accounting.
- **Bootstrap prompt:**
  > Implement SUB-14 Stage D (plan §5.19 party primitive, engine-owned FTB
  > Teams pattern): party API + store, ranks/allies, API-only creation,
  > join/leave events, member-only progress sharing. Acceptance: TCK
  > `party_progress_isolation` on ≥2 drivers.

### Stage E — Session-type extension point & testmod demo (M3)

- [ ] **Do:** `SessionTypeDescriptor` registration via sub-02 (Java + JSON);
  rules-factory lookup; testmod "timed hunt" demo session.
- **Acceptance:** JSON registration on one cell, Java on another; demo runs
  created→completed with a party; session TCK suite green on the two 1.21.1
  cells pre-M4 (deferred 26.x re-run on M4 landing, acceptance-matrix rule).
- **Touches:** `vine-api` descriptor, `vine-core` type wiring, `vine-testmod`.
- **Bootstrap prompt:**
  > Implement SUB-14 Stage E. Register `SessionTypeDescriptor` (+Codec, Java +
  > JSON paths) via sub-02; add the testmod timed-hunt demo. Acceptance: both
  > registration paths exercised, demo completes, session TCK suite green on
  > both 1.21.1 cells (26.x re-run deferred to M4).

## 4. Problems & blockers

- **Replication scoping:** decided — `SessionState` to participants only;
  `ParticipantState` owner-only unless opted public; spectator visibility
  deferred until a consumer demands it (owner: this file).
- **Unload policy:** suspend (persist) default; terminate-on-unload per-type
  opt-in; world deletion abandons; rules notified either way.
- **Party reconciliation on membership change:** server authority,
  last-writer-wins on store conflicts; join merges shared progress only for
  opted-in session types; leave snapshots contribution, stops accrual.
  This policy is shared with sub-15, whose quest-progress reconciliation
  references it (owner: this file). Edge cases (locked): (a) a player leaving
  a party mid-session with personal contribution keeps the snapshot taken at
  leave — accrual stops there; the snapshot is neither lost nor re-merged,
  and the session continues for the remaining members. (b) Party
  merge/disband while a session is active: merge re-keys `SessionScope.Party`
  to the surviving party (participants re-resolved from the new membership;
  mirrors sub-15's highest-progress-wins per counter); disband abandons
  PARTY-scoped sessions (`ABANDONED`, rules notified) — mirroring sub-15's
  disband = archive rule.
- **LAN vs dedicated parity:** integrated server runs the identical
  `SessionService`; TCK session scenarios run on both integrated and
  dedicated runtimes.
- **Reconnect mid-session:** re-login routes through the late-join snapshot
  path; `onParticipantJoined` must tolerate re-join (javadoc invariant).

## 5. Verification

TCK scenarios owned: `session_lifecycle`, `session_late_join`,
`session_persistence` (golden `VoxelData` fixture, cross-cell readable),
`party_progress_isolation` — each green on ≥2 drivers, one per loader family
(§8), before the surface ships. Performance budget: O(participants) diff
packets; zero packets on ticks with no state change. Session HUD checklist
items belong to sub-16, not this file.

## 6. Agent guidance

- **Conventions:** packages `dev.vineengine.vine.session|party` (public) /
  `dev.vineengine.vine.internal.session|party`; shared modules Java 21.
  Descriptors are data. Consumers subclass `SessionRules`, never the session.
- **Comment policy:** javadoc states invariants (rules server-only, never
  replicated; re-join tolerance); driver comments name the per-cell
  saved-data difference absorbed.
- **Forbidden:** version-string parsing; loader types in API signatures;
  Mixins outside drivers; client authority over outcomes; replicating
  `SessionRules`.
- **Done means:** stage checkboxes ticked, acceptance green on all four
  cells, status `done`, dashboard row in `docs/README.md` updated.
