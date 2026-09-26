package dev.vineengine.vine.quest;

import java.util.UUID;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.VoxelData;

/**
 * A registrable reward kind (sub-15 §2, §5.20): its parameters' codec and how it is paid.
 *
 * <p><b>Granted after the claim is recorded, never before.</b> The engine writes "reward 2
 * of this quest is claimed" into the player's tree and only then calls {@link #grant} — so a
 * crash between the two loses a reward rather than duplicating it, which is the failure
 * direction a player can be compensated for.
 *
 * @param paramsCodec how this reward's parameters are read from JSON
 * @param grant       how the reward is paid to one player
 */
public record RewardType<P>(Codec<P> paramsCodec, Grant<P> grant) {

    /** Pays one reward to one player. */
    @FunctionalInterface
    public interface Grant<P> {

        /**
         * @param player the player being paid
         * @param params this reward's own parameters
         * @return a human-readable line the engine logs and a probe can assert
         */
        String grant(UUID player, P params);
    }
}
