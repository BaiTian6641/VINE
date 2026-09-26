# SUB-10 — Combat & input

> **Status:** `in-progress` — one of `planning | in-progress | blocked(<reason>) | done`
> **Milestone:** M2 (Stage E: M4, 26.x) · **Depends on:** SUB-05, SUB-08, SUB-09 · **Blocks:** —
> **Cells:** all · **Loaders:** both
> **Master plan:** §5.15 (+ §5.8, §5.13, §5.14) · **Module(s):** vine-api, vine-core, vine-spi, drivers

## 1. Purpose

Ordered **input/action API** (descriptors, validated client→server requests, deterministic ordering, input buffering, combo/cancel windows) plus an **ordered combat pipeline** — sweep → part resolution → modifiers → application — replacing per-loader hurt/attack event soup (§5.15). Non-goals: lag compensation / client hit prediction (v1 strict server-authoritative, §4); vanilla combat for unregistered content (Minimal Footprint, §5.1); 3D hit presentation (sub-17); keybind presentation (sub-16's client half).

## 2. Design

- **API surface** (`vine-api`, `dev.vineengine.vine.combat`):

```java
/** Design descriptor (§5.2); windows read against sub-09 evaluator timings. */
public record ActionDescriptor(VineId id, int bufferTicks, int comboWindowTicks,
    Set<VineId> cancelInto, int cooldownTicks, VineId animationId) {
  public static final Codec<ActionDescriptor> CODEC = ...; }

/** One strike; motion value multiplies the weapon's base damage. */
public record AttackDescriptor(VineId id, double motionValue, VineId damageType,
    VineId element, SweepShape sweep) {  // damageType: sever/blunt/shot-style
  public static final Codec<AttackDescriptor> CODEC = ...; }

public record ActionRequest(VineId action, long clientTick, InputSnapshot input) {}

public interface ActionHandler {  // consumer's server-side contract per action
  ValidationResult validate(ActorRef actor, ActionRequest req);
  void perform(ActorRef actor, ActionRequest req, ActionContext ctx);
}

/** MODIFY-phase extension point (§5.20); deterministic registration order. */
public interface CombatModifier { HitContext modify(HitContext ctx); }
public record HitContext(AttackDescriptor attack, ActorRef attacker, PartRef part,
    double damage, Knockback knockback, int hitstopTicks) { /* withers */ }

public interface CombatState {    // engine-owned, server-authoritative
  boolean inIFrames(ActorRef target);
  void grantIFrames(ActorRef target, int ticks);
  void applyHitstop(ActorRef target, int ticks); // freezes VineBrain + physics
}
```

- **Internals** (`vine-core`): `ActionSequencer` — per-actor order `(serverTick, clientTick, actorJoinIndex)`, one in-flight action per actor; validated requests wait in the **server-side** input buffer (`bufferTicks`) and execute when the current action's combo/cancel window opens (startup/active/recovery/combo-cancel timings come from the sub-09 server evaluator — animation is data driving combat timing, §5.14). `CombatPipeline` — fixed phases: **SWEEP** (attack OBB vs part colliders positioned by the sub-09 evaluator; parts and per-part wound state from sub-08) → **RESOLVE** (hitzone multiplier + part state) → **MODIFY** (registered `CombatModifier`s: skills/affinities) → **APPLY** (i-frame check → damage → knockback override → hitstop). Damage stays `double` end-to-end; rounds half-up only at APPLY.
- **Driver contract** (`vine-spi`): map loader hurt/attack hooks into the pipeline once (§4 table); suppress vanilla attack-cooldown for opted-in items only; deal final damage through the native damage path under a reentrancy guard so engine-dealt damage never re-enters the pipeline. 1.21.1: NF `AttackEntityEvent` / `LivingIncomingDamageEvent`; Fabric `AttackEntityCallback` / `AllowDamage`. 26.x: unobfuscated equivalents; accessors over Mixins where possible (§5.9).
- **Sync:** requests ride a sub-05 channel; server→client emits presentation facts (hit confirmed, i-frame flash, hitstop ticks) consumed by sub-16/17. Hit *results* never travel client→server — the server computes sweeps itself.
- **Combat ownership (locked 2026-09-26):** every item that deals melee damage
  declares an owner as data — `VINE` or a named partner (`BETTER_COMBAT`). The
  engine installs sweep/cooldown/windows for `VINE`-owned items only; for a
  partner-owned item it installs **none** of them, cooks the partner's own data
  file from the same descriptor (Better Combat reads
  `data/<ns>/weapon_attributes/<item>.json` presets), and consumes only the
  *result*, which is what routes a partner-driven hit into SWEEP→RESOLVE→MODIFY→
  APPLY without either system double-handling it. This is not a preference: Better
  Combat's own documentation states that implementing your own attack range,
  cooldown, dual-wield, input or player-animation logic on the same item
  *semantically conflicts* with it (verified 2026-09-26, its 1.21.1 README), and
  two pipelines that both claim an item cannot be merged — only assigned. The
  partner is soft: absent, partner-owned items are simply rejected at
  registration with a readable error rather than silently falling back to engine
  rules the author did not ask for.

## 3. Stages

### Stage A — Descriptors & registration

- [ ] **Do:** descriptor records + `Codec`s (vine-api); design-descriptor wiring, Java + JSON paths (vine-core).
- **Acceptance:** testmod registers one action + one attack via Java and JSON; boot log lists both IDs on both 1.21.1 cells.
- **Touches:** vine-api `combat`, vine-core registry glue.
- **Bootstrap prompt:**
  > Implement sub-10 Stage A per this file + docs/README.md conventions (Java 21, Prime Invariant, §5.2 design descriptors). Acceptance: dual Java+JSON registration boots on 1.21.1-NF + 1.21.1-Fabric.

### Stage B — Action channel, sequencing, windows

- [ ] **Do:** sub-05 channel + request codec; `ActionSequencer` (ordering, ≤10 req/s/actor rate limit, cooldown check); server input buffer + combo/cancel runtime on sub-09 timings (never re-derive).
- **Acceptance:** headless TCK: scripted out-of-order/over-rate flood yields an identical golden decision log on both 1.21.1 drivers.
- **Touches:** vine-core `internal.combat`, vine-spi channel hooks.
- **Bootstrap prompt:**
  > Implement sub-10 Stage B on Stage A: validate (cooldown, rate, actor state), then buffer server-side. Acceptance: golden decision-log TCK green on both 1.21.1 cells. Conventions docs/README.md.

### Stage C — Pipeline core & combat state

- [ ] **Do:** `CombatPipeline` (SWEEP→RESOLVE→MODIFY→APPLY); `CombatModifier` extension registry; sweep query over sub-09 colliders + sub-08 part state; `CombatState` (i-frame/hitstop counters, knockback override); presentation facts on sub-05.
- **Acceptance:** driver-free JVM fixtures: §5 worked example replays exactly; i-framed target rejects a second hit.
- **Touches:** vine-core `internal.combat.pipeline`.
- **Bootstrap prompt:**
  > Implement sub-10 Stage C: fixed/closed phases, consumer extension only via `CombatModifier`/`ActionHandler`, half-up rounding at APPLY; i-frames/hitstop engine-owned, never vanilla invulnerability timers for opted-in content. Acceptance: both JVM fixtures pass; no loader code. Conventions docs/README.md.

### Stage D — 1.21.1 driver normalization (NF ∥ Fabric — parallel)

- [ ] **Do:** map loader events per §4; reentrancy guard; opt-in scoping; vanilla cooldown suppression for opted-in items only; hitstop/knockback SPI (`freezeActor`, `applyKnockback`).
- **Acceptance:** end-to-end TCK: §5 attack on a multipart testmod entity → 110 damage, head part wounds, hitstop gap in tick-trace; adjacent unregistered mob behaves pure-vanilla; and with Better Combat installed, a `BETTER_COMBAT`-owned testmod weapon produces exactly one hit routed through part resolution (no double handling) while a `VINE`-owned weapon produces exactly one through the engine path.
- **Touches:** driver-1.21.1-neoforge, driver-1.21.1-fabric.
- **Bootstrap prompt:**
  > Implement sub-10 Stage D for your one cell (see header): translate loader damage hooks into the Stage C pipeline once; engine-dealt damage sets the reentrancy flag; unregistered content stays behaviorally vanilla (§5.1). Acceptance: end-to-end TCK green on your cell. Conventions docs/README.md.

### Stage E — 26.x drivers (M4)

- [ ] **Do:** port Stage D to both 26.x drivers (unobfuscated runtime, Java 25, accessors over Mixins).
- **Acceptance:** full sub-10 TCK green, identical damage numbers, both 26.x cells.
- **Touches:** driver-26.x-neoforge, driver-26.x-fabric.
- **Bootstrap prompt:**
  > Implement sub-10 Stage E: mirror the 1.21.1 normalization (read both drivers first); version differences stay inside these drivers. Acceptance: full sub-10 TCK green on 26.x-NF + 26.x-Fabric. Conventions docs/README.md.

### Stages A + C — Descriptors and the pipeline (landed)

- [x] **Do:** `ActionDescriptor` / `AttackDescriptor` / `SweepShape` (box and arc) /
  `CombatProfile` + `CombatOwnership` (the ownership declaration, with a partner preset required
  for partner-owned items), `HitContext` with withers, `CombatModifier` as the only MODIFY-phase
  extension, engine-owned `CombatState` (i-frames, hitstop, tick-decayed), and the closed
  SWEEP → RESOLVE → MODIFY → APPLY pipeline with half-up rounding applied exactly once and a
  reentrancy flag around APPLY. `CombatActorRef` lets a *player* swing, which is what makes the
  subsystem usable from a loader's attack hook at all.
- **Evidence:** the headless `combat.txt` golden (the plan's worked example to the digit:
  40 × 1.6 × 1.3 × 1.1 × 1.2 = 109.824 → 110, plus the cooked partner preset bytes), and
  `entity_combat_pipeline` on a live cell (110 damage on the head part, i-framed second hit
  refused at APPLY, an adjacent vanilla cow untouched).
- **Fixed while building it:** `CombatStateImpl.decay` counted windows down with
  `Map.Entry#setValue`, which a `ConcurrentHashMap` rejects — it threw on the server tick,
  once per tick, for as long as any window was open.

### Stage D — 1.21.1 driver normalization (landed, in part)

- [x] **Do:** per-cell attack/damage hooks (`NeoForgeMeleeHooks`, `FabricCombatHooks`) that
  cancel a `VINE`-owned weapon's vanilla attack and run the pipeline, route a
  `BETTER_COMBAT`-owned weapon's native damage through it exactly once, apply the result through
  the loader's own damage path under the reentrancy guard, leave every unregistered weapon and
  target alone, tick `CombatState` once per server tick, and cook the partner preset in
  `datagenContent`.
- **Landed with a live client (NeoForge 1.21.1, 2026-09-26; Fabric pending the blocker below):**
  a scripted client world → forceload → beast spawned and hosted → `/tp` the player two blocks
  south of it, facing it → `/give` + `select_slot` → one `attack`, then the engine's own
  `tck_parts_status` line:
  - **VINE-owned weapon** (`vine_test:ember_blade`, Better Combat absent): head wound `0 → 83`
    (40 base × 1.6 motion × 1.3 head hit-zone = 83.2, rounded half-up), tail untouched —
    exactly one strike, through the engine's own chain rather than the client's claim.
  - **Better Combat-owned weapon** (`vine_test:partner_blade`, Better Combat + its deps installed
    in the dev client): the same `+83` on the head, never 166. Better Combat's own weapon-registry
    log names the item as `{"attributes":{"attack_range":4.5}}` — the engine-cooked claymore
    preset — where its fallback (`bettercombat:sword`) would have written `0.0`. The engine
    installed no sweep for the item and consumed the partner's swing exactly once.
  - **Yaw convention (normative for cell authors):** `VineCombat.strike` takes yaw in the pose
    evaluator's convention — yaw 0 puts the actor's forward (model −Z) at world −Z — while a
    Minecraft player's yaw 0 faces world +Z, so a cell passes `player.getYaw() + 180`.
    Negative control: with the conversion removed, the identical swing at the identical aim
    lands **no** wound.
  - **Who ships the cooked preset:** cooking is engine-side and cell-independent (both cells'
    datagen emit byte-identical bytes, sha256 `c55cb4e5…`), but a preset written only to
    `build/datagen` reaches no running game. The pack that owns the item ships it — a real pack
    through its own datagen, the testmod by shipping that same file in its jar — and the run above
    is evidence of the shipped path, not of a bridge jar built for the test.
  - **A stance is not a claim:** the same attacker, weapon and aim from thirty blocks away reports
    `MISSED` and moves no wound (`TwoPlayerFight`), and the two players' strikes sum exactly on
    the shared state rather than doubling.
- **Blocked on a cell bug (Fabric):** the scripted Fabric client holds zero engine entities while
  the server reports the beast spawned and hosted — the client never receives the entity, so no
  swing can be aimed at it. Root-caused to the cell's spawn/tracking path; fix in progress.
- **Open question — hit flakiness:** roughly one run in three lands nothing while the client
  provably swings at the right entity; the suspect is the sweep's top edge against the idle clip's
  head box (the intersection is only ~0.4–1.1 blocks deep). Whether that is a fixture-geometry
  problem or an engine sweep problem is being decided from evidence.

## 4. Problems & blockers

- **Loader semantics differ** — normalize to one ACCEPT/REJECT verdict (normative table; extend it, never fork pipeline logic per loader):

  | Concern | NeoForge | Fabric | Normalization |
  |---|---|---|---|
  | Pre-attack cancel | `AttackEntityEvent` cancel | `AttackEntityCallback` FAIL | reject before SWEEP |
  | Damage modification | `LivingIncomingDamageEvent` (1.21.1 name) | *(no damage-modification event — quarantined driver Mixin, §5.9)* | absorbed by MODIFY |
  | Post-damage | `LivingDamageEvent.Post` | `AfterDamage` | presentation facts only |
- **Combat-partner landscape (verified 2026-09-26):**

  | | Better Combat | Epic Fight |
  |---|---|---|
  | 1.21.1 loaders | Fabric **and** NeoForge | **NeoForge only** |
  | Integration data | `data/<ns>/weapon_attributes/<item>.json` presets (`parent`, `range_bonus`, per-attack `range_multiplier`, `hitbox: "arc"`, animation ids) | `data/<ns>/capabilities/weapons/<item>.json` (`type`, `common`/`one_hand`/`two_hand` attributes) |
  | Java API | `net.bettercombat.api` (stable presets; JSON suffices for most mods) | `yesman.epicfight.api` (registry-based `WeaponCapability.Builder`; docs call it still stabilizing) |
  | 26.x | alive (API present on its 26.2 branch) | different release axis |
  | Scope | player weapons + animation | whole soulslike engine (stamina, movesets, colliders) |

  Better Combat is therefore the **parity-safe** partner (both cells, both
  milestones) and the only one the ownership rule must bridge for the RPG target;
  Epic Fight stays an optional NeoForge-only partner behind the same capability
  probe, and VINE never makes a Fabric cell depend on it.

- **Latency policy (locked v1):** strict server-authoritative; **no lag compensation, no rewind**. Client may animate wind-up immediately; damage lands on server confirm. Revisit only with a documented exemplar-consumer need; decision owner: this file.
- **Vanilla cooldown interplay:** suppressed per opted-in item; engine cooldown is `ActionDescriptor.cooldownTicks`. Foreign mods read vanilla cooldown as 1.0 — documented, accepted for opted-in items.
- **Cheat resistance:** server recomputes sweeps (never trusts client hit claims); per-actor rate limit; requests outside cooldown/active windows rejected; sweep sanity = attacker→target within OBB + 0.5-block epsilon. **Proven live:** an out-of-reach strike from the same attacker, weapon and aim reports `MISSED` and moves no wound (sub-10 Stage D).
- **Reentrancy:** pipeline-dealt damage re-entering loader hooks — engine reentrancy flag checked at every driver entry point; TCK asserts exactly one pipeline pass per hit.

## 5. Verification

Owned TCK scenarios (§7): action-request golden decision log; **worked-example pipeline trace**; i-frame/hitstop; multipart routing end-to-end; Minimal Footprint probe. Normative worked example (motion value × hitzone × modifiers → final damage):

```
base 40 × motion 1.6 (attack)      → 64.0
× hitzone 1.3  (head part, sub-08) → 83.2
× affinity +10% (modifier)         → 91.52
× element weakness 1.2 (modifier)  → 109.824
APPLY: i-frames? no → round half-up → 110 damage
       wound threshold 100 exceeded → part breaks
       knockback override (2.5, up 0.4); hitstop 3 ticks
```

Client smoke: buffered combo chains on a 200 ms-delay client; hitstop freeze-frames visible via sub-16/17 hooks. No surface ships without a TCK scenario on ≥2 drivers, one per loader family (§8).

## 6. Agent guidance

- **Conventions:** `dev.vineengine.vine.combat` (API) / `dev.vineengine.vine.internal.combat` (core, drivers); shared modules Java 21, 26.x drivers Java 25. Fixed pipeline phases; behavior enters via extension points only. Descriptors are data (Java + JSON).
- **Comment policy:** javadoc states why + invariants ("buffer is server-side so authority never leaves the server"); driver comments name the exact loader event normalized.
- **Forbidden:** version-string parsing (probes only, §5.12); loader types in API; Mixins outside drivers; touching vanilla combat for unregistered content (§5.1); client-computed hit results.
- **Done means:** checkboxes ticked, TCK green on all four cells, status `done`, dashboard row updated in `docs/README.md`.
