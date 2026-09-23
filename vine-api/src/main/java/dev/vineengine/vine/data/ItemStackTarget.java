package dev.vineengine.vine.data;

/**
 * One item-stack attach point: portable-strategy data rides the engine-owned
 * {@code vine:voxel_data} component on this stack (sub-03 §2).
 *
 * <p>Stage C seam: the payload is the per-cell stack object until sub-07
 * ships the engine item-stack facade; drivers interpret, signatures never
 * name a game type.
 */
public record ItemStackTarget(Object stack) implements VoxelTarget {

    public ItemStackTarget {
        java.util.Objects.requireNonNull(stack, "stack");
    }
}
