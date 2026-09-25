package dev.vineengine.vine.entity;

import java.util.Objects;
import java.util.UUID;

import dev.vineengine.vine.registry.VineId;

/**
 * One live entity instance (sub-08 Stage C): the descriptor it was spawned from plus
 * the engine-assigned instance id. A {@link VineId} alone identifies a <em>kind</em>,
 * and two dragons of the same kind are not the same actor — every per-instance thing
 * the engine tracks (a brain, later a part state) is keyed by this pair.
 *
 * <p>The instance id is chosen by the engine, never by a cell: it is the engine's
 * identity currency, and a cell that invented its own would make the same entity two
 * actors.
 *
 * @param entityId the descriptor this instance was spawned from
 * @param instance the engine-assigned per-instance id
 */
public record VineEntityRef(VineId entityId, UUID instance) {

    public VineEntityRef {
        Objects.requireNonNull(entityId, "entityId");
        Objects.requireNonNull(instance, "instance");
    }

    @Override
    public String toString() {
        return entityId + "#" + instance;
    }
}
