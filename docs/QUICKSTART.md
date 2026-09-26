# VINE quick start

Five things you can build today, each with the smallest consumer-side code that does it,
the data file it needs, and the command that proves it works on this machine.

Everything below is **consumer code**: `vine-api` types only, no loader or Minecraft
imports, identical on every cell. The engine's own testmod runs the same code paths —
its exemplars live in `vine-testmod/src/main/java/dev/vineengine/vine/testmod/` and are the
scenario probes for these features.

## 1. A creature with parts you can hit

```java
// Spawn it (the engine picks the instance id and tells you its ref).
VineEntityRef beast = VineEntities.spawn(
    VineId.of("mymod", "wyvern"), VineWorlds.overworld(), Vec3.of(4.5, 300.0, 0.5)).orElseThrow();

// Host its parts, driven by a clip of an asset you parsed through the engine.
AnimationAsset asset = VineAnimations.parse(json);       // the authoring file, engine-parsed
VineParts.attach(beast, asset, "animation.mymod.wyvern.idle");

// Hit a part: the multiplier, the wound, the break and the flinch are the engine's.
VineParts.PartHit hit = VineParts.applyHit(beast, "tail", 50.0, VineId.parse("minecraft:player_attack"))
    .orElseThrow();
if (hit.broke()) {
    // Swap the animation set, drop loot, whatever the design says. The engine already
    // flipped the part's stored state, so a reload keeps it broken.
}
boolean staggered = VineParts.consumeFlinch(beast);      // a hit that interrupts a behaviour
```

Data: `data/mymod/vine/entity/wyvern.json` declares `parts` (name, parent bone, size,
offset, per-damage-type multipliers, flinch and break thresholds).

Prove it: `cmd /c "gradlew.bat :vine-tck:runScenarios1211Fabric"` — the `entity_parts`
scenario asserts the wound, the break, the flinch and that the box follows the pose.

## 2. A weapon that follows the engine's rules

```java
// The descriptor (Java path; the same record is what JSON decodes into).
VineRegistries.register(VineContent.ATTACK_TYPE, CLEAVE, new AttackDescriptor(CLEAVE,
    1.6, VineId.parse("minecraft:player_attack"), VineId.of("mymod", "ember"),
    SweepShape.Box.of(2.8, 1.2, 1.2), Vec3.of(2.0, 0.0, 0.0), 3));
VineRegistries.register(VineContent.ITEM_TYPE, BLADE, new ItemDescriptor(BLADE,
    new ItemTuning(1), ModelHint.generated(), Optional.of(CombatProfile.vine(CLEAVE, 40.0))));

// Your game's rules are modifiers, in registration order.
VineCombat.addModifier(context -> context.multiplyDamage(1.1));   // affinity
VineCombat.addModifier(context -> context.multiplyDamage(1.2));   // element weakness

// Server-side, on the swing the loader reported:
CombatResult result = VineCombat.strike(CombatActorRef.player(playerId), beastRef, CLEAVE,
    40.0, playerPosition, playerYaw);
```

Data: one item JSON with a `combat` block. A **partner-owned** weapon
(`"owner": "better_combat"`, `"partnerPreset": "bettercombat:claymore"`) makes the engine
cook that mod's own preset from the same descriptor and install none of its own sweep — see
`PartnerPresetCooking`.

Prove it: `entity_combat_pipeline` asserts the worked example end to end
(`40 × 1.6 × 1.3 × 1.1 × 1.2 → 110`), the i-framed second hit, and an adjacent vanilla mob
left untouched.

## 3. A quest chain with progression

```java
VineRegistries.register(VineQuests.QUEST_TYPE, HUNT, new QuestDescriptor(HUNT, CHAPTER,
    display("Boar Hunt"), List.of(FIRST_STEPS),
    List.of(new ObjectiveInstance(OBJECTIVE, VineId.of("vine", "count"), params, 3)),
    List.of(new RewardInstance(VineId.of("vine", "xp"), xpParams)),
    RepeatPolicy.ONCE));

VineQuests.start(player, HUNT);                        // refused until its dependency is claimed
VineQuests.fireEvent(MY_KILL_EVENT, player, payload);  // batched; coalesced per tick
VineQuests.tick();                                     // the server calls this every tick
List<VineId> paid = VineQuests.claim(player, HUNT);    // recorded before it is granted
int level = VineQuests.level(player);                  // progression rides the same tree
```

Data: `data/mymod/vine/quest/*.json` and `quest_chapter/*.json`. Progress lives in the
world's engine store, so it survives a save/reload with nobody logged in.

Prove it: `entity_campaign_beat` runs a two-quest beat, saves and reloads the world, and
asserts the second player's progress never moved.

## 4. A cutscene the server drives

```java
VineCutscenes.play(VineId.of("mymod", "wyvern_strike"), Set.of(playerA, playerB));
// ... each server tick:
VineCutscenes.tick();
// A consumer can ride the frames without owning a renderer:
VineCutscenes.addFrameListener((viewer, frame) -> rumble(viewer, frame.camera().position()));
```

Data: `data/mymod/vine/cutscene/wyvern_strike.json` — a camera track plus actor, audio and
title tracks. Every viewer is sent the same frame for the same tick.

Prove it: the `cutscene.frames.txt` golden pins the evaluation tick by tick; the
`entity_cutscene` scenario asserts two viewers received identical frames.

## 5. A screen the engine lays out

```java
VineRegistries.register(VineContent.SCREEN_TYPE, BOARD, new ScreenDescriptor(BOARD,
    new Widget.Group(List.of(
        new Widget.Button(ACCEPT, "Accept", LayoutSpec.at(0, 0, 110, 18)),
        new Widget.Button(LEAVE, "Leave", LayoutSpec.at(0, 0, 110, 18)),
        new Widget.Label("No hunts posted", LayoutSpec.at(0, 0, 110, 10))),
        2, 20, 120, new LayoutSpec(0, 0, 240, 20, Anchor.CENTER)),
    false));
```

Data: `data/mymod/vine/screen/*.json`. Layout is resolved by the engine in logical pixels
(`UiLayout.resolve`), so both cells place a button in the same spot; the `ui.layout.txt`
golden pins it.

## 6. A scripted client run

The engine ships a scripted client: a JSON step list the dev client executes (`create_world`,
`wait`, `command`, `play_cutscene`, `open_screen`, `screenshot`, `quit`), so a client-visible
feature can be shown working without a human at the keyboard.

```
cmd /c "gradlew.bat :drivers:driver-1.21.1-fabric:vineTckClient -Pvine.tck.clientScript=C:/Users/weyst/VINE/vine-tck/client/1.21.1-fabric.json"
cmd /c "gradlew.bat :drivers:driver-1.21.1-neoforge:vineTckClient -Pvine.tck.clientScript=C:/Users/weyst/VINE/vine-tck/client/1.21.1-neoforge.json"
```

With the property absent the client behaves exactly as a normal dev client (no auto-quit). A
step that cannot be performed stops the run with one line naming it and a non-zero exit — a cell
with no materialization for the descriptor it names refuses `open_screen` rather than skipping
it. `open_screen` completes once the screen is up and closes it `waitTicks` client ticks later,
so the step after it (a screenshot) sees the screen. Screenshots land in the cell's
`run/screenshots/`; committed copies live in `vine-tck/fixtures/client/`.

Two facts the cells had to learn, worth knowing before you write a script: a materialized
engine entity type with **no** client renderer crashes the client the moment it is in view
(both cells register an empty renderer for every engine type), and a harness-launched client
loses window focus, so `pauseOnLostFocus` is cleared for scripted runs or every step waits on a
paused server. A fresh world spawns at a random spot, so a script that works at fixed
coordinates starts with a `forceload`.

## Verify everything

```
cmd /c "gradlew.bat build"
cmd /c "gradlew.bat :vine-tck:evaluatorFixtures"
cmd /c "gradlew.bat :vine-tck:runScenarios1211Fabric :vine-tck:runScenarios1211Neoforge"
cmd /c "gradlew.bat :vine-tck:verifyDatagen"
```

Scenario runners write into shared run directories: run one Gradle-driven server at a time.

Client acceptance scripts live in `vine-tck/client/`, one per cell and concern: a baseline world
(cutscene, HUD layer, screen with buttons), a swing with a VINE-owned weapon, a swing with a
Better Combat-owned weapon, and a two-player fight where one player's far strike must miss. They
are driven with `-Pvine.tck.clientScript=<absolute path>`; each prints the engine's own lines
(`tck: parts status head=…`, `tck: two-player …`) and writes a screenshot, so a run is read from
the engine's numbers rather than from the input that caused them.

The two Better Combat scripts need the partner staged in that cell's `run/mods/` (dev-client only,
never a compile dependency): Better Combat 2.4.0+1.21.1, Cloth Config 15.0.140 and Player Animator
2.0.4+1.21.1, per loader, from Modrinth. Delete them again for the VINE-owned scripts: with Better
Combat installed it takes over any weapon's swing, so a VINE-owned acceptance run must not have it.

## Who ships a cooked partner preset

The engine cooks the partner's file (`data/<ns>/weapon_attributes/<item>.json`) because the
translation is data; **shipping it is the pack's job**, through that pack's own data pack (both
cells' `datagenContent` show the wiring, and `:vine-tck:verifyDatagen` proves the bytes match the
goldens). The testmod ships the same bytes in its jar, but a dev-run *library* is not a mod, so its
`data/` never mounts as a data pack: with no bridge present, Better Combat reports no `vine_test`
entry and falls back to its own rules. Measured on both cells, 2026-09-26 — see sub-10.
