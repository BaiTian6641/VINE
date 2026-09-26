package dev.vineengine.vine.quest;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import dev.vineengine.vine.registry.VineId;

/**
 * One player's progress through one quest (sub-15 §2): a value, read out of the player's
 * own stored tree, so a quest that survives a reload needs no separate mechanism.
 *
 * @param quest           the quest
 * @param state           where it stands
 * @param objectiveCounts objective id → count so far, in the descriptor's own order
 * @param rewardsClaimed  the reward indices already granted
 */
public record QuestProgress(VineId quest, ProgressState state, Map<VineId, Integer> objectiveCounts,
        Set<Integer> rewardsClaimed) {

    public QuestProgress {
        Objects.requireNonNull(quest, "quest");
        Objects.requireNonNull(state, "state");
        objectiveCounts = Map.copyOf(Objects.requireNonNull(objectiveCounts, "objectiveCounts"));
        rewardsClaimed = Set.copyOf(Objects.requireNonNull(rewardsClaimed, "rewardsClaimed"));
    }

    /** A quest nobody has touched: locked until its dependencies say otherwise. */
    public static QuestProgress locked(VineId quest, List<VineId> objectives) {
        java.util.LinkedHashMap<VineId, Integer> counts = new java.util.LinkedHashMap<>();
        for (VineId objective : objectives) {
            counts.put(objective, 0);
        }
        return new QuestProgress(quest, ProgressState.LOCKED, counts, Set.of());
    }

    /** This progress with one objective's count set. */
    public QuestProgress withCount(VineId objective, int count) {
        java.util.LinkedHashMap<VineId, Integer> counts = new java.util.LinkedHashMap<>(objectiveCounts);
        counts.put(objective, count);
        return new QuestProgress(quest, state, counts, rewardsClaimed);
    }

    public QuestProgress withState(ProgressState newState) {
        return new QuestProgress(quest, newState, objectiveCounts, rewardsClaimed);
    }

    public QuestProgress withRewardClaimed(int index) {
        java.util.LinkedHashSet<Integer> claimed = new java.util.LinkedHashSet<>(rewardsClaimed);
        claimed.add(index);
        return new QuestProgress(quest, state, objectiveCounts, claimed);
    }

    /** The count for one objective, or zero when the descriptor never declared it. */
    public int count(VineId objective) {
        return objectiveCounts.getOrDefault(objective, 0);
    }

    /** Whether {@code player} may claim this quest's rewards right now. */
    public boolean claimable() {
        return state == ProgressState.COMPLETED;
    }
}
