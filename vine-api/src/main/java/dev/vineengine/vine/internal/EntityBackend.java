package dev.vineengine.vine.internal;

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
     * dimension. The engine validates that the descriptor is registered before the
     * cell is asked, so a cell never has to answer for content the engine does not
     * know.
     */
    boolean spawn(VineId entityId, VineWorld world, Vec3 position);
}
