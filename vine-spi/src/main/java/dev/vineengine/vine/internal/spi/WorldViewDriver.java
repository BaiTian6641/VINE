package dev.vineengine.vine.internal.spi;

import java.util.Optional;

import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;

/**
 * The driver-side world-view SPI (sub-07 Stage B "Driver contract"): how the
 * engine reads and moves block state in a cell's live world. One per cell,
 * implemented only by VINE's own driver jars, bound once during driver bootstrap
 * via vine-core's {@code WorldViewBinding} — the mirror of the storage seam's
 * exactly-one-bind rule.
 *
 * <p><b>What a driver owns:</b> resolving a dimension id to that cell's level,
 * resolving an engine block id to the native block it materialized, and mapping
 * engine property values onto native state values. All three are mapping work,
 * never policy: the engine rejects over-budget state models, invalid states and
 * unregistered blocks before this interface is called, so a driver implementation
 * can treat its inputs as already valid.
 *
 * <p><b>Invariants:</b> {@link #stateAt} never throws for an ordinary world
 * condition — an unloaded dimension, an unloaded chunk, a position outside the
 * build height and a position holding a non-engine block all report
 * {@link Optional#empty()}. {@link #setState} reports whether the position holds
 * the requested state afterwards ({@code true} also when it already did), and
 * returns {@code false} rather than throwing when the cell refused the write
 * (unloaded dimension, position not holding that state's block). Both methods are
 * called on the server thread; a driver that needs the main thread defers
 * internally rather than returning a guessed answer.
 */
public interface WorldViewDriver {

    /** The engine block state at {@code pos}, or empty when no engine block is there. */
    Optional<BlockState> stateAt(VineId dimensionId, BlockPos pos);

    /**
     * Applies {@code state} at {@code pos} and reports whether the position holds
     * it afterwards.
     *
     * @return {@code false} when the write was refused (dimension not loaded,
     *         position does not hold that state's block, or the cell reported the
     *         change as not applied)
     */
    boolean setState(VineId dimensionId, BlockPos pos, BlockState state);

    /** Whether {@code dimensionId} is loaded by this cell right now. */
    boolean isLoaded(VineId dimensionId);

    /**
     * The engine data attach point of the block entity at {@code pos}, or empty when
     * the position holds no holder (the block is not an engine block, it declares no
     * block entity, or the chunk is not loaded).
     *
     * <p>The engine turns this into the holder's {@code VoxelData} tree (the same
     * attach point storage, sync and save use), which is how a behavior's tick
     * context gets its data and how a consumer reads a placed block's tree without
     * ever naming a native type. Directions: a driver answers with the target its own
     * storage path would use for that holder, and reports empty rather than throwing
     * for every ordinary world condition.
     */
    Optional<VoxelTarget> holderTarget(VineId dimensionId, BlockPos pos);
}
