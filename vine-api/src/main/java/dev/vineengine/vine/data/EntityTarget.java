package dev.vineengine.vine.data;

/**
 * One entity attach point: data rides NF data attachments / Fabric
 * custom-data under the {@code vine} key (sub-03 §2).
 *
 * <p>Stage C seam: the payload is the per-cell entity object until sub-08
 * ships the engine entity facade; drivers interpret, signatures never name a
 * game type.
 */
public record EntityTarget(Object entity) implements VoxelTarget {

    public EntityTarget {
        java.util.Objects.requireNonNull(entity, "entity");
    }
}
