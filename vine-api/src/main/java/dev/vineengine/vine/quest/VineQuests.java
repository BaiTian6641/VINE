package dev.vineengine.vine.quest;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;

import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.QuestBackend;
import dev.vineengine.vine.registry.VineId;

/**
 * Static entry point for quests and progression (sub-15 §2). Mirror of the other facades:
 * a consumer declares chapters, quests, objective and reward types as data, fires the events
 * its gameplay produces, and asks about progress — the engine owns the counters, the state
 * machine and the claim bookkeeping.
 *
 * <p><b>Authority.</b> Progress is server-side and per player. A client never sends a count;
 * it sends an event (through whatever API produced it) and the server decides what it
 * advanced. The same rule as combat, for the same reason.
 *
 * <p><b>Progression lives here too</b> because quests pay it: XP and levels are a single
 * running total per player, and skills are named levels a game reads back. Both are stored in
 * the player's own tree, so a reload needs no separate mechanism.
 */
public final class VineQuests {

    /** The chapter kind: design registry {@code vine:quest_chapter}. */
    public static final DescriptorType<ChapterDescriptor> CHAPTER_TYPE =
        QuestTypes.chapterType();

    /** The quest kind: design registry {@code vine:quest}. */
    public static final DescriptorType<QuestDescriptor> QUEST_TYPE = QuestTypes.questType();

    /** How much total XP each level needs: level {@code n} starts at {@code 100 * n * n}. */
    public static final int XP_PER_LEVEL_SQUARED = 100;

    private VineQuests() {
    }

    /** Registers an objective kind; a second registration of the same id is refused. */
    public static <P> void registerObjectiveType(VineId id, ObjectiveType<P> type) {
        backend().registerObjectiveType(Objects.requireNonNull(id, "id"), Objects.requireNonNull(type, "type"));
    }

    /** Registers a reward kind. */
    public static <P> void registerRewardType(VineId id, RewardType<P> type) {
        backend().registerRewardType(Objects.requireNonNull(id, "id"), Objects.requireNonNull(type, "type"));
    }

    /**
     * Reports that something happened to {@code player} — a kill, a pickup, a delivery.
     * Events are batched to the next {@link #tick()}: a flood of identical events in one
     * server tick advances the objective once per event, but through a single pass over the
     * player's quests.
     */
    public static void fireEvent(VineId eventType, UUID player, VoxelData payload) {
        backend().fireEvent(Objects.requireNonNull(eventType, "eventType"), Objects.requireNonNull(player, "player"),
            Objects.requireNonNull(payload, "payload"));
    }

    /** One player's progress through one quest. */
    public static QuestProgress progress(UUID player, VineId quest) {
        return backend().progress(Objects.requireNonNull(player, "player"), Objects.requireNonNull(quest, "quest"));
    }

    /** Every quest the player has started and not finished, in declaration order. */
    public static List<QuestProgress> active(UUID player) {
        return backend().active(Objects.requireNonNull(player, "player"));
    }

    /**
     * Starts a quest for {@code player} if its dependencies are claimed.
     *
     * @return whether the quest is active afterwards
     */
    public static boolean start(UUID player, VineId quest) {
        return backend().start(Objects.requireNonNull(player, "player"), Objects.requireNonNull(quest, "quest"));
    }

    /**
     * Claims a completed quest's rewards, in declaration order. Each reward is recorded as
     * claimed before it is paid, so claiming twice cannot pay twice.
     *
     * @return the reward type ids granted by this call, empty when the quest was not claimable
     */
    public static List<VineId> claim(UUID player, VineId quest) {
        return backend().claim(Objects.requireNonNull(player, "player"), Objects.requireNonNull(quest, "quest"));
    }

    /**
     * Abandons {@code player}'s progress through {@code quest} — counts, state and claim
     * records — so it can be started again.
     *
     * <p>A real game needs this ("start over", "reset the hunt"), and a scenario needs it to
     * be runnable twice: a beat that asserts absolute numbers is only honest if it begins
     * from a known state.
     *
     * @return whether there was anything to abandon
     */
    public static boolean abandon(UUID player, VineId quest) {
        return backend().abandon(Objects.requireNonNull(player, "player"), Objects.requireNonNull(quest, "quest"));
    }

    /**
     * Flushes the pending event batch. The engine's own server tick calls this; a headless
     * harness calls it directly, which is what makes a campaign beat a scenario.
     */
    public static void tick() {
        backend().tick();
    }

    /**
     * Registers a listener for "this player completed this quest" — the hook a cutscene, a
     * sound or an achievement hangs off. Listeners run after the state is stored, so a
     * listener that asks for the progress reads the completed state.
     */
    public static void addCompletionListener(BiConsumer<UUID, VineId> listener) {
        backend().addCompletionListener(Objects.requireNonNull(listener, "listener"));
    }

    /** The player's total XP. */
    public static int xp(UUID player) {
        return backend().xp(Objects.requireNonNull(player, "player"));
    }

    /** The player's level, derived from {@link #xp}: {@code floor(sqrt(xp / 100))}. */
    public static int level(UUID player) {
        return levelForXp(xp(player));
    }

    /** Adds XP and reports the level afterwards. */
    public static int addXp(UUID player, int amount) {
        return backend().addXp(Objects.requireNonNull(player, "player"), amount);
    }

    /** A named skill's level (affinity, profession, resistance — whatever a game calls it). */
    public static int skill(UUID player, VineId skill) {
        return backend().skill(Objects.requireNonNull(player, "player"), Objects.requireNonNull(skill, "skill"));
    }

    /** Sets a named skill's level. */
    public static void setSkill(UUID player, VineId skill, int level) {
        backend().setSkill(Objects.requireNonNull(player, "player"), Objects.requireNonNull(skill, "skill"), level);
    }

    /** The level a total XP amount corresponds to — the curve, in one place. */
    public static int levelForXp(int xp) {
        return xp <= 0 ? 0 : (int) Math.floor(Math.sqrt(xp / (double) XP_PER_LEVEL_SQUARED));
    }

    private static QuestBackend backend() {
        if (EngineAccess.get() instanceof QuestBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide quests (headless runtime, or vine-core is not the engine)");
    }
}
