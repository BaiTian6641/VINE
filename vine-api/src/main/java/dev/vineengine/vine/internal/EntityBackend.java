package dev.vineengine.vine.internal;

import java.util.Optional;

import dev.vineengine.vine.brain.VineBrain;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorld;

/**
 * Internal bridge from {@code VineEntities} (vine-api) to vine-core's
 * registry-aware entity calls. NOT public API — implemented once by vine-core's
 * engine object; never implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess}, so the engine's exactly-one-provider
 * rule, boot ordering and cached boot failure apply to entity calls unchanged
 * (same pattern as {@link VoxelBackend} and {@link WorldBackend}).
 */
public interface EntityBackend {

    /**
     * Spawns one entity of {@code entityId} at {@code position} in {@code world}'s
     * dimension and returns its engine identity. The engine validates that the
     * descriptor is registered before the cell is asked, so a cell never has to answer
     * for content the engine does not know, and the engine chooses the instance id so
     * identity never depends on a cell's own numbering.
     *
     * @return the new instance's ref, or empty when the cell refused (dimension not
     *         loaded, position outside the world)
     */
    Optional<VineEntityRef> spawn(VineId entityId, VineWorld world, Vec3 position);

    /**
     * Attaches {@code brain} to a live instance (sub-08 Stage C): the cell's per-tick
     * actor callback then ticks it through {@code EntityRuntime}. Replacing an
     * attached brain is refused — a running brain's actions would be discarded
     * mid-flight.
     *
     * @throws IllegalArgumentException if no live instance carries {@code ref}
     */
    void attach(VineEntityRef ref, VineBrain brain);

    /** Drops whatever is attached to {@code ref}; idempotent (a removed entity calls it). */
    void detach(VineEntityRef ref);
}
