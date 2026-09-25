package dev.vineengine.vine.world;

import java.util.Optional;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

/**
 * An engine view over one world (sub-07 Stage B): the only surface a consumer
 * uses to read or move block state. Engine-owned and loader-free — the driver
 * resolves the native level and the native block state behind it, so consumer
 * code is identical on every cell and never names a game class.
 *
 * <p><b>Scope:</b> only VINE blocks are addressable. A position holding a vanilla
 * block, an unloaded chunk or an out-of-range height reports an
 * {@linkplain Optional#empty() absent state} — never an exception, and never a
 * silently invented state. {@link #setState} refuses a state whose block is not
 * the block at that position (a state is moved onto its own block, not swapped
 * across blocks).
 *
 * <p>A view is a handle, not a snapshot: it stays valid for the process lifetime
 * and reads the world as it is at call time. Values it returns are immutable
 * {@link BlockState} snapshots.
 */
public interface VineWorld {

    /** The dimension id this view addresses (e.g. {@code minecraft:overworld}). */
    VineId id();

    /**
     * Whether the dimension is loaded by the running cell right now. A world that
     * is not loaded answers {@linkplain Optional#empty() absent} for every
     * position rather than failing.
     */
    boolean isLoaded();

    /**
     * The engine block state at {@code pos}, or empty when the position does not
     * hold an engine block (vanilla block, unloaded chunk, outside the world).
     */
    Optional<BlockState> stateAt(BlockPos pos);

    /**
     * Moves {@code state} onto {@code pos}, the engine's single state-mutation
     * call.
     *
     * @return {@code true} when the position now holds that state (including when
     *         it already did); {@code false} when the write was refused — the
     *         dimension is not loaded, the position does not hold that state's
     *         block, or the cell reported the change as not applied
     * @throws IllegalArgumentException if {@code state}'s block is not a
     *         registered engine block, or its schema does not match the
     *         descriptor's declared properties (a schema mismatch is a caller bug,
     *         not a runtime condition)
     */
    boolean setState(BlockPos pos, BlockState state);

    /**
     * The engine data tree of the block entity at {@code pos}, or empty when the
     * position holds no holder (not an engine block, no block-entity declaration, or
     * nothing loaded there).
     *
     * <p>The tree is opened through the engine's own storage path against the schema
     * the block's descriptor declares, so it is the same object the block's behaviors
     * see in their tick context and the same one the save path persists — reading it
     * here is not a copy and not a second source of truth.
     */
    Optional<VoxelData> dataAt(BlockPos pos);
}
