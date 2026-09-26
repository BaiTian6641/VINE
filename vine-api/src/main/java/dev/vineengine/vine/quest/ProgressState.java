package dev.vineengine.vine.quest;

/**
 * Where one quest stands for one player (sub-15 §2). Four states, in order, and no
 * fifth: a quest is locked until its dependencies are met, active while it is being
 * worked on, completed when every objective is done, and claimed once its rewards have
 * been taken — which is the last thing that can happen to it.
 *
 * <p>Deliberately not a boolean pair: "completed but unclaimed" is a real and common
 * moment (a player standing in front of a camp chest), and a model that cannot name it
 * invents a flag every consumer would then have to agree about.
 */
public enum ProgressState {

    /** Dependencies are not met. */
    LOCKED,

    /** Under way; objectives count up from here. */
    ACTIVE,

    /** Every objective is done; rewards are waiting. */
    COMPLETED,

    /** Rewards taken; the quest is spent (unless it repeats). */
    REWARDS_CLAIMED;

    /** Whether this state is at or past {@code other} in the quest's life. */
    public boolean atLeast(ProgressState other) {
        return ordinal() >= other.ordinal();
    }
}
