package dev.vineengine.vine.internal;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.quest.ObjectiveType;
import dev.vineengine.vine.quest.QuestProgress;
import dev.vineengine.vine.quest.RewardType;
import dev.vineengine.vine.registry.VineId;

/**
 * Internal bridge from {@code VineQuests} (vine-api) to vine-core's quest service. NOT
 * public API — implemented once by vine-core's engine object; never implemented or
 * referenced by consumers.
 */
public interface QuestBackend {

    /** See {@code VineQuests#registerObjectiveType}. */
    <P> void registerObjectiveType(VineId id, ObjectiveType<P> type);

    /** See {@code VineQuests#registerRewardType}. */
    <P> void registerRewardType(VineId id, RewardType<P> type);

    /** See {@code VineQuests#fireEvent}. */
    void fireEvent(VineId eventType, UUID player, VoxelData payload);

    /** See {@code VineQuests#progress}. */
    QuestProgress progress(UUID player, VineId quest);

    /** See {@code VineQuests#active}. */
    List<QuestProgress> active(UUID player);

    /** See {@code VineQuests#start}. */
    boolean start(UUID player, VineId quest);

    /** See {@code VineQuests#claim}. */
    List<VineId> claim(UUID player, VineId quest);

    /** See {@code VineQuests#abandon}. */
    boolean abandon(UUID player, VineId quest);

    /** See {@code VineQuests#tick}. */
    void tick();

    /** See {@code VineQuests#addCompletionListener}. */
    void addCompletionListener(BiConsumer<UUID, VineId> listener);

    /** See {@code VineQuests#xp}. */
    int xp(UUID player);

    /** See {@code VineQuests#addXp}. */
    int addXp(UUID player, int amount);

    /** See {@code VineQuests#skill}. */
    int skill(UUID player, VineId skill);

    /** See {@code VineQuests#setSkill}. */
    void setSkill(UUID player, VineId skill, int level);
}
