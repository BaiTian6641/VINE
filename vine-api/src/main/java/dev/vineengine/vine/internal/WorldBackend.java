package dev.vineengine.vine.internal;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.VineWorld;

/**
 * Internal bridge from {@code VineWorlds} (vine-api) to vine-core's
 * registry-aware world views. NOT public API — implemented once by vine-core's
 * engine object; never implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess} rather than its own ServiceLoader
 * seam, so the engine's exactly-one-provider rule, boot ordering, and cached boot
 * failure apply to world calls unchanged (same pattern as {@link VoxelBackend}).
 */
public interface WorldBackend {

    /**
     * A view over {@code dimensionId}. The engine validates the dimension id and
     * hands the driver's raw view to {@code VineWorld}, which adds descriptor
     * validation on every state it accepts.
     */
    VineWorld world(VineId dimensionId);
}
