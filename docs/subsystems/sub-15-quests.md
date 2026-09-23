# SUB-15 — Quests & activities

> **Status:** `planning` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M3 · **Depends on:** SUB-02, SUB-14 (SUB-16 soft — default GUI) · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.21, §5.2, §5.19 · **Module(s):** vine-api | vine-client-api | vine-core | vine-spi | drivers

## 1. Purpose

A quest system as **platform surface** (FTB Quests/FTB Teams precedent,
§5.21): chapters/quests/objectives/rewards authored as datapack JSON,
hot-reloadable, server→client synced, party-aware, with engine-shipped
objective/reward types and extension points for consumer-defined types.
Consumers compose quest boards, camps, and activity chains without re-solving
progress authority, replication, or presentation. **Non-goals:** quest
authoring UIs, dialogue/story systems, per-party instanced content (§5.18),
game-design content itself (boards/stations are consumer composition, §5.21).

## 2. Design

**API** (`vine-api`, `dev.vineengine.vine.quest`) — design descriptors
(sub-02 machinery, `Codec` records, dynamic datapack registries):

```java
public record ChapterDescriptor(VineId id, int schemaVersion,                // reserved, §11.10 schema governance
                                VoxelData display, List<VineId> quests, int order) {}
public record QuestDescriptor(VineId id, int schemaVersion,                  // reserved, §11.10 schema governance
                              VineId chapter, VoxelData display,
                              List<VineId> dependencies,          // chains; empty = root
                              List<ObjectiveInstance> objectives,
                              List<RewardInstance> rewards,
                              RepeatPolicy repeat) {}             // ONCE | REPEATABLE(cooldown)
public record ObjectiveInstance(VineId type, VoxelData params, int count) {}
public record RewardInstance(VineId type, VoxelData params) {}

/** Owned here (README vocabulary). Party-scoped progress view over one quest. */
public record QuestProgress(VineId quest, ProgressState state,
                            Map<VineId, Integer> objectiveCounts, // objective id -> count
                            Set<UUID> rewardsClaimed) {}
public enum ProgressState { LOCKED, ACTIVE, COMPLETED, REWARDS_CLAIMED }
// PartyRef + SessionFactory are sub-14 types (README vocabulary) — referenced, never redefined here.

// Extension points (§5.20): consumers register new types without touching engine code
public interface ObjectiveType<P> { Codec<P> paramsCodec(); ObjectiveBehavior<P> behavior(); }
public interface RewardType<P>    { Codec<P> paramsCodec(); void grant(RewardContext ctx, P params); }
public interface Quests {
    <P> void registerObjectiveType(VineId id, ObjectiveType<P> type);
    <P> void registerRewardType(VineId id, RewardType<P> type);
    QuestProgress progressOf(PartyRef party, VineId quest);   // party-aware (sub-14)
    void fireCustomEvent(VineId eventType, PlayerRef source, VoxelData payload);
    void linkSession(VineId questId, SessionFactory factory); // quest start → VineSession create
}
```
JSON example (§5.2 layout — `data/mymod/vine/quest_chapter/trials.json` +
`data/mymod/vine/quest/hunt_boar.json`):

```json
{ "schemaVersion": 1,
  "display": { "title": "Trials", "icon": "mymod:trial_sigil" }, "order": 1,
  "quests": ["mymod:first_steps", "mymod:hunt_boar"] }
```

```json
{ "schemaVersion": 1,
  "chapter": "mymod:trials", "display": { "title": "Boar Hunt", "icon": "mymod:boar_trophy" },
  "dependencies": ["mymod:first_steps"], "repeat": { "kind": "repeatable", "cooldown": "PT24H" },
  "objectives": [
    { "type": "vine:kill",    "params": { "entity": "mymod:boar" }, "count": 5 },
    { "type": "vine:deliver", "params": { "item": "mymod:boar_tusk", "to": "mymod:hunt_master" }, "count": 2 }
  ],
  "rewards": [
    { "type": "vine:xp", "params": { "levels": 3 } },
    { "type": "vine:items", "params": { "items": [{ "id": "mymod:hunter_charm", "count": 1 }] } }
  ] }
```

**Internals** (`vine-core`): `QuestProgressStore` — `VoxelData`-persisted
(sub-03) per-party progress, objective counters keyed `(party, quest,
objective)`. Event intake is **batched + deduplicated** per tick (kill/collect
floods coalesce before counter updates). Party sharing resolves through
sub-14's party primitive: **members share progress, allies don't**.
Membership-change reconciliation: progress accrues to the *party*, not the
player; on member leave, the leaver carries only their reward-claim history
(no partial progress fork); on merge/disband, sub-14's party events trigger
progress re-keying with highest-progress-wins per objective counter.
Presentation: engine owns data + tracking + a default GUI built on the sub-16
2D toolkit; consumers reskin via `QuestPresentation` hooks in
`vine-client-api`.

**Driver contract** (`vine-spi` `QuestDriver`): `emitQuestRegistries`
(`DataPackRegistryEvent.NewRegistry` on NF / `DynamicRegistries.registerSynced`
on Fabric, §5.2), `objectiveEventSources` (kill/collect/interact/deliver taps —
per-loader event names differ, absorbed in drivers, Mixin-quarantined §5.9),
`syncProgress` (sub-05 packets), `executeCommandRewards` (server command
context). No per-cell behavioral difference is visible to consumers.

**Data flow:** datapack JSON → dynamic registries → `/reload` rebuilds the
quest graph (cycles rejected at load) → events batched into counters → delta
sync to tracking clients → rewards granted server-side on completion →
`linkSession` quests create a `VineSession` (sub-14) at quest start and feed
session outcomes back as objective events.

## 3. Stages

### Stage A — Quest descriptor registries & JSON

- [ ] **Do:** chapter/quest/objective/reward descriptor records + `Codec`s;
  dynamic-registry emission in both 1.21.1 drivers; dependency graph
  validation (cycle reject) at load; `schemaVersion` field reserved on every
  descriptor (§11.10).
- **Acceptance:** testmod ships the §2 JSON example; quest loads, `/reload`
  edits apply, synced to client; cyclic dependency rejected with clear log.
- **Touches:** vine-api `quest` pkg, vine-core `internal.quest.desc`, vine-spi, drivers.
- **Bootstrap prompt:**
  > Implement SUB-15 Stage A per docs/subsystems/sub-15-quests.md §2: the four
  > descriptor records with Codecs in `dev.vineengine.vine.quest`, dynamic
  > datapack registries via sub-02, dependency-graph validation, and emission
  > in both 1.21.1 drivers (NF `DataPackRegistryEvent.NewRegistry`, Fabric
  > `DynamicRegistries.registerSynced`). Acceptance as above. Binding: Prime
  > Invariant; descriptors are data (§5.2).

### Stage B — Objective & reward type extension points

- [ ] **Do:** `ObjectiveType`/`RewardType` registries; the six built-in
  objective types + four built-in reward types; driver event taps for
  kill/collect/interact/deliver.
- **Acceptance:** all built-in types work in the testmod quest; a testmod
  *custom* objective type and reward type register and function without
  engine edits.
- **Touches:** vine-api, vine-core `internal.quest.types`, drivers.
- **Bootstrap prompt:**
  > Implement SUB-15 Stage B: the objective/reward type registries and the
  > built-in types (kill/collect/reach/deliver/interact/custom-event;
  > items/xp/commands/session-outcome) per sub-15-quests.md §2, with driver
  > event taps. Acceptance: built-ins pass in-game; a consumer-registered
  > custom type works with zero engine changes.

### Stage C — Party-aware progress engine

- [ ] **Do:** `QuestProgressStore` (VoxelData), party-scoped counters via
  sub-14 party primitive, membership-change reconciliation policy, event
  batching/dedup, offline-progress policy (accrue on next login against
  elapsed-time-capped backlog — see §4).
- **Acceptance:** two party members share a kill counter; an ally outside the
  party does not; member leave/join mid-quest keeps counters consistent;
  progress survives save/load.
- **Touches:** vine-core `internal.quest.progress`, sub-03, sub-14 events.
- **Bootstrap prompt:**
  > Implement SUB-15 Stage C per sub-15-quests.md §2/§4: VoxelData-persisted,
  > party-scoped progress with member-share/ally-exclude semantics, the
  > stated membership-change reconciliation policy, and per-tick event
  > batching/dedup. Acceptance: all stage bullets pass on both 1.21.1
  > loaders.

### Stage D — Tracking, sync & presentation

- [ ] **Do:** tracked-quest selection per player; delta sync to clients
  (sub-05); default quest GUI on the sub-16 2D toolkit; `QuestPresentation`
  reskin hooks in vine-client-api.
- **Acceptance:** progress updates appear in the default GUI within one sync
  tick; a testmod reskin replaces chapter styling without engine edits.
- **Touches:** vine-client-api, vine-core `internal.quest.sync`, sub-16 hooks.
- **Bootstrap prompt:**
  > Implement SUB-15 Stage D: tracked-quest delta sync via sub-05 and the
  > default 2D-toolkit quest GUI (sub-16) with `QuestPresentation` reskin
  > hooks. Acceptance: GUI updates live on progress; testmod reskin applies.
  > Client surface stays loader-free (Prime Invariant).

### Stage E — Rewards, chains, repeatability & session triggers

- [ ] **Do:** reward execution (items/xp/commands/session outcomes) with
  per-player claim semantics inside shared party completion; dependency
  gating; repeatable/cooldown quests; `linkSession` quest-start →
  `VineSession` creation and session-outcome feedback.
- **Acceptance:** chain quest unlocks only after its dependency completes;
  cooldown quest re-offers after cooldown; linked quest starts a session and
  its outcome advances an objective.
- **Touches:** vine-core `internal.quest.{reward,session}`, sub-14 API.
- **Bootstrap prompt:**
  > Implement SUB-15 Stage E per sub-15-quests.md §2: reward execution,
  > dependency gating, repeat/cooldown policy, and the VineSession trigger
  > link (quest start → session create; session outcome → objective event).
  > Acceptance: all stage bullets pass on both 1.21.1 loaders.

### Stage F — TCK scenario & hot-reload hardening

- [ ] **Do:** TCK quest scenario (load → progress → complete → reward →
  repeat-cooldown) on ≥2 drivers, one per loader family; reload-during-active-
  progress policy (counters preserved across descriptor edits; removed
  objectives archived).
- **Acceptance:** TCK scenario green on 1.21.1-NF + 1.21.1-Fabric; `/reload`
  mid-quest preserves counters.
- **Touches:** vine-tck scenario, vine-core reload path.
- **Bootstrap prompt:**
  > Author SUB-15 Stage F: the TCK quest scenario covering the full lifecycle
  > plus `/reload` mid-progress counter preservation. Acceptance: scenario
  > passes on one driver per loader family; counters survive descriptor edits.

## 4. Problems & blockers

- **Progress divergence on membership change mid-quest**: two parties must not
  both claim progress earned while merged. Policy (locked here): progress
  belongs to the party; leave = no carry-over except reward-claim history;
  disband = archive; merge = highest-progress-wins per counter, logged.
  Owner: this file.
- **JSON schema versioning** (open question §11.10): descriptors reserve a
  `schemaVersion` int from day one; engine rejects unknown higher versions
  with a clear error; migrations deferred until v2 exists — do not build a
  datafixer yet. Owner: sub-02 governance + this file.
- **Objective event-flood perf**: kill/collect farms can fire thousands of
  events/tick. Intake batches per tick and dedups identical `(type, target)`
  events before counter updates; budget checked inside the sub-13 perf
  scenario harness.
- **Offline progress accumulation**: events while a member is offline must not
  silently grant or lose progress. Policy: party-shared counters accrue
  regardless (progress is the party's); *personal* trigger sources
  (e.g. reach-location) queue per player, applied at next login with a
  configurable elapsed-time cap (default 7 days).

## 5. Verification

- TCK quest lifecycle scenario (Stage F) on ≥2 drivers, one per loader family —
  the ship gate for every surface here (§8).
- Hot-reload: `/reload` edits descriptors with counters preserved; cycles
  rejected.
- Party semantics: member-share/ally-exclude + membership-change reconciliation
  covered by scenario steps, not just unit tests.
- Perf: objective intake inside the sub-13 perf scenario tick budget.
- Client smoke: default GUI tracking/reskin on one cell per loader family.

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.quest.*` (public) /
  `dev.vineengine.vine.internal.quest.*`; Java 21 shared, Java 25 in 26.x
  drivers; descriptors are data (`Codec`, Java + JSON paths, §5.2).
- **Comment policy:** javadoc states *why* + invariants (progress is
  party-owned; allies never share); driver comments name the per-loader event
  source absorbed.
- **Forbidden:** version-string parsing; loader/`net.minecraft.*` types in API
  signatures; Mixins outside drivers; engine code edited to add a
  consumer objective type (extension points exist precisely to prevent this).
- **Done means:** all boxes ticked, acceptance green on all four cells, status
  `done`, dashboard row updated in `docs/README.md`.
