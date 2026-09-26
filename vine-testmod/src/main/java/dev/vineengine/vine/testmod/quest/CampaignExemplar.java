package dev.vineengine.vine.testmod.quest;

import java.util.List;
import java.util.UUID;

import com.google.gson.JsonObject;

import dev.vineengine.vine.quest.ChapterDescriptor;
import dev.vineengine.vine.quest.ObjectiveInstance;
import dev.vineengine.vine.quest.QuestDescriptor;
import dev.vineengine.vine.quest.QuestProgress;
import dev.vineengine.vine.quest.RepeatPolicy;
import dev.vineengine.vine.quest.RewardInstance;
import dev.vineengine.vine.quest.VineQuests;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

/**
 * The sub-15 exemplar: a two-quest campaign beat driven entirely through the engine's own
 * API — start, objectives, completion, rewards, progression — plus the completion hook a
 * cutscene hangs off.
 *
 * <p>The quests are registered from the consumer side (Java path) and mirrored by JSON
 * under {@code data/vine_test/vine/quest/}; the scenario asserts both resolve, because
 * "authored as data" is only true when the datapack path really produces the same entry.
 *
 * <p>Two players run the same beat: one does the work, the other only watches. Per-player
 * isolation is the property that makes a campaign a campaign rather than a shared score.
 */
public final class CampaignExemplar {

    /** The chapter the two quests live in. */
    public static final VineId CHAPTER = VineId.of("vine_test", "trials");

    /** The first quest: two events, paid in XP. */
    public static final VineId FIRST_STEPS = VineId.of("vine_test", "first_steps");

    /** The second quest: depends on the first, three events, paid in XP and a skill level. */
    public static final VineId HUNT_BOAR = VineId.of("vine_test", "hunt_boar");

    /** The event type both objectives count. */
    private static final VineId HUNT_EVENT = VineId.of("vine_test", "hunt");

    /** The skill the second quest grants. */
    private static final VineId HUNTER_SKILL = VineId.of("vine_test", "hunter");

    /** The player who does the work, and the one who only watches. */
    private static final UUID ACTOR = UUID.nameUUIDFromBytes("vine:tck:campaign".getBytes());
    private static final UUID WATCHER = UUID.nameUUIDFromBytes("vine:tck:campaign:watcher".getBytes());

    /** XP/level/skill before this run, so the beat asserts what it earned rather than a total. */
    private static int xpBaseline;
    private static int levelBaseline;
    private static int skillBaseline;

    private CampaignExemplar() {
    }

    /** The schema of one hunt event's payload — engine data, so it is registered, not implied. */
    private static final VineId EVENT_SCHEMA = VineId.of("vine_test", "hunt_event");

    /** Registers the chapter and both quests, plus the completion hook a cutscene uses. */
    public static void register() {
        // A schema must exist before a tree can be created under it: the engine refuses to
        // guess, which is exactly the error the first run of this exemplar produced.
        dev.vineengine.vine.data.VineData.registerSchema(
            new dev.vineengine.vine.data.VoxelSchema(EVENT_SCHEMA, 1, com.mojang.serialization.Codec.unit(null)),
            java.util.List.of());
        JsonObject chapterDisplay = new JsonObject();
        chapterDisplay.addProperty("title", "Trials");
        VineRegistries.register(VineQuests.CHAPTER_TYPE, CHAPTER,
            new ChapterDescriptor(CHAPTER, chapterDisplay, List.of(FIRST_STEPS, HUNT_BOAR), 1));

        VineRegistries.register(VineQuests.QUEST_TYPE, FIRST_STEPS, new QuestDescriptor(FIRST_STEPS, CHAPTER,
            display("First Steps"), List.of(),
            List.of(objective("steps", 2)),
            List.of(reward("vine:xp", 400)),
            RepeatPolicy.ONCE));
        VineRegistries.register(VineQuests.QUEST_TYPE, HUNT_BOAR, new QuestDescriptor(HUNT_BOAR, CHAPTER,
            display("Boar Hunt"), List.of(FIRST_STEPS),
            List.of(objective("boars", 3)),
            List.of(reward("vine:xp", 900), skillReward()),
            RepeatPolicy.ONCE));

        VineQuests.addCompletionListener((player, quest) -> System.out.println(
            "tck: campaign cutscene-trigger player=" + player + " quest=" + quest
                + " state=" + VineQuests.progress(player, quest).state()));
    }

    /**
     * Runs the beat and prints one line per fact the scenario asserts: start, objectives,
     * completion, claim, progression — and the same beat attempted for the watching player,
     * which must not move.
     */
    public static void run() {
        // A beat that asserts absolute numbers has to begin from a known state: an earlier
        // run's world keeps its claim records, and refusing to pay a claimed reward twice is
        // the engine behaving correctly, not a failing scenario.
        boolean abandoned = VineQuests.abandon(ACTOR, FIRST_STEPS) | VineQuests.abandon(ACTOR, HUNT_BOAR);
        VineQuests.abandon(WATCHER, FIRST_STEPS);
        // Progression is world-level, so a beat that ran before leaves XP behind: the beat
        // asserts what *this* run earned, which is the property a player would recognise and
        // the only one a repeated gate can check honestly.
        xpBaseline = VineQuests.xp(ACTOR);
        levelBaseline = VineQuests.level(ACTOR);
        skillBaseline = VineQuests.skill(ACTOR, HUNTER_SKILL);
        watcherBaseline = VineQuests.xp(WATCHER);
        System.out.println("tck: campaign abandon=" + abandoned + " xpBaseline=" + xpBaseline
            + " levelBaseline=" + levelBaseline + " skillBaseline=" + skillBaseline);
        System.out.println("tck: campaign start first=" + VineQuests.start(ACTOR, FIRST_STEPS)
            + " huntBeforeDependency=" + VineQuests.start(ACTOR, HUNT_BOAR));
        fire(ACTOR, 2);
        System.out.println("tck: campaign afterEvents state=" + VineQuests.progress(ACTOR, FIRST_STEPS).state()
            + " count=" + VineQuests.progress(ACTOR, FIRST_STEPS).count(VineId.of("vine_test", "steps")));
        System.out.println("tck: campaign claimFirst=" + VineQuests.claim(ACTOR, FIRST_STEPS)
            + " state=" + VineQuests.progress(ACTOR, FIRST_STEPS).state()
            + " xpGained=" + (VineQuests.xp(ACTOR) - xpBaseline)
            + " levelsGained=" + (VineQuests.level(ACTOR) - levelBaseline));

        System.out.println("tck: campaign start huntAfterClaim=" + VineQuests.start(ACTOR, HUNT_BOAR));
        fire(ACTOR, 3);
        System.out.println("tck: campaign hunt state=" + VineQuests.progress(ACTOR, HUNT_BOAR).state());
        System.out.println("tck: campaign claimHunt=" + VineQuests.claim(ACTOR, HUNT_BOAR)
            + " state=" + VineQuests.progress(ACTOR, HUNT_BOAR).state()
            + " xpGained=" + (VineQuests.xp(ACTOR) - xpBaseline)
            + " levelsGained=" + (VineQuests.level(ACTOR) - levelBaseline)
            + " skill=" + VineQuests.skill(ACTOR, HUNTER_SKILL));

        // The same events for the watcher: their own quests must not exist, let alone advance.
        fire(WATCHER, 2);
        QuestProgress watcherProgress = VineQuests.progress(WATCHER, FIRST_STEPS);
        System.out.println("tck: campaign isolation watcherState=" + watcherProgress.state()
            + " watcherCount=" + watcherProgress.count(VineId.of("vine_test", "steps"))
            + " watcherXpDelta=" + (VineQuests.xp(WATCHER) - watcherBaseline)
            + " activeActor=" + VineQuests.active(ACTOR).size()
            + " activeWatcher=" + VineQuests.active(WATCHER).size());

        // Reload-equivalent: the progress the engine would write is readable from the player's
        // own tree, which is what a save/reload round trip restores.
        System.out.println("tck: campaign persisted xp=" + VineQuests.xp(ACTOR)
            + " level=" + VineQuests.level(ACTOR)
            + " firstState=" + VineQuests.progress(ACTOR, FIRST_STEPS).state()
            + " huntState=" + VineQuests.progress(ACTOR, HUNT_BOAR).state()
            + " claims=" + VineQuests.progress(ACTOR, HUNT_BOAR).rewardsClaimed().size());
    }

    /**
     * Reads the beat's outcome back — the assertion a {@code SaveReloadWorld} step makes
     * meaningful: these numbers come from the world store, not from anything this process
     * remembers.
     */
    public static void verify() {
        // Stable facts only: XP totals are world-level and accumulate across runs, so the
        // reload is asserted through what the beat itself fixed — states, counters, claims.
        System.out.println("tck: campaign reloaded firstState=" + VineQuests.progress(ACTOR, FIRST_STEPS).state()
            + " huntState=" + VineQuests.progress(ACTOR, HUNT_BOAR).state()
            + " huntCount=" + VineQuests.progress(ACTOR, HUNT_BOAR).count(VineId.of("vine_test", "boars"))
            + " claims=" + VineQuests.progress(ACTOR, HUNT_BOAR).rewardsClaimed().size()
            + " skill=" + VineQuests.skill(ACTOR, HUNTER_SKILL)
            + " watcherState=" + VineQuests.progress(WATCHER, FIRST_STEPS).state()
            + " progressionKept=" + (VineQuests.xp(ACTOR) > 0));
    }

    /** The watcher's XP before the beat, so their isolation is asserted as a delta too. */
    private static int watcherBaseline;

    /** Queues {@code count} hunt events and flushes them — the batching the service promises. */
    private static void fire(UUID player, int count) {
        for (int i = 0; i < count; i++) {
            VineQuests.fireEvent(HUNT_EVENT, player, eventPayload());
        }
        VineQuests.tick();
    }

    /** One hunt event's payload: the shape the objective's behaviour reads. */
    private static dev.vineengine.vine.data.VoxelData eventPayload() {
        dev.vineengine.vine.data.VoxelData payload =
            dev.vineengine.vine.data.VineData.create(EVENT_SCHEMA);
        payload.put("amount", 1);
        return payload;
    }

    private static ObjectiveInstance objective(String name, int count) {
        JsonObject params = new JsonObject();
        // What this objective counts: the event a hunt fires. An objective's *type* is its
        // kind ("count these"), so the thing being counted is data.
        params.addProperty("event", HUNT_EVENT.toString());
        return new ObjectiveInstance(VineId.of("vine_test", name), VineId.of("vine", "count"), params, count);
    }

    private static RewardInstance reward(String type, int amount) {
        JsonObject params = new JsonObject();
        params.addProperty("amount", amount);
        return new RewardInstance(VineId.parse(type), params);
    }

    private static RewardInstance skillReward() {
        JsonObject params = new JsonObject();
        params.addProperty("skill", HUNTER_SKILL.toString());
        params.addProperty("level", 1);
        return new RewardInstance(VineId.of("vine", "skill"), params);
    }

    private static JsonObject display(String title) {
        JsonObject display = new JsonObject();
        display.addProperty("title", title);
        return display;
    }
}
