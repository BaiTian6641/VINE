package dev.vineengine.vine.content;

import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;
import dev.vineengine.vine.world.VineWorld;

/**
 * The engine view of one loot event (sub-07 Stage C): which block, where, in what
 * state, and — when the cell can say — who broke it. A cell that has no breaker
 * (explosions, piston-driven drops, world edits) reports an empty actor rather than
 * inventing one.
 *
 * @param world  the world the block was removed from
 * @param pos    the block's position
 * @param state  the block's engine state when it was removed
 * @param miner  the player responsible, when the cell knows one
 */
public record LootContext(VineWorld world, BlockPos pos, BlockState state, Optional<VinePlayer> miner) {

    public LootContext {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        miner = Objects.requireNonNull(miner, "miner");
    }
}
