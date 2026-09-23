package dev.vineengine.vine.data;

/**
 * One block-entity attach point: data lives in the reserved {@code vine}
 * sub-compound of the BE's save tag (sub-03 §2), stripped from vanilla sync
 * unless the field is client-visible.
 *
 * <p>Stage C seam: the payload is the per-cell block-entity object until
 * sub-07 ships the engine BE facade; drivers interpret, signatures never
 * name a game type.
 */
public record BlockEntityTarget(Object blockEntity) implements VoxelTarget {

    public BlockEntityTarget {
        java.util.Objects.requireNonNull(blockEntity, "blockEntity");
    }
}
