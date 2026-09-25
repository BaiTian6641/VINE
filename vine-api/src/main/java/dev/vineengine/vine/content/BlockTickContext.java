package dev.vineengine.vine.content;

import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;
import dev.vineengine.vine.world.VineWorld;

/**
 * The engine view of one block tick (sub-07 Stage C). {@link #data()} is present
 * exactly when the block declares a block-entity descriptor, and it is the holder's
 * own tree — the same attach point the storage, sync and save paths use, so a
 * ticking behavior persists whatever it writes there without any extra mechanism.
 *
 * <p>The tree is opened by the cell once per holder, not per tick: a behavior may
 * cache nothing itself (behavior instances are shared across placements), but the
 * context it receives is already bound to this holder's live tree.
 *
 * @param world the world the tick happened in
 * @param pos   the block's position
 * @param state the block's engine state at {@code pos} when the tick began
 * @param kind  which of the three normalized tick signals produced this call
 * @param data  the holder's engine tree, or empty when the block has no block
 *              entity (a random- or scheduled-tick-only behavior)
 */
public record BlockTickContext(VineWorld world, BlockPos pos, BlockState state, TickKind kind,
        Optional<VoxelData> data) {

    public BlockTickContext {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(kind, "kind");
        data = Objects.requireNonNull(data, "data");
    }
}
