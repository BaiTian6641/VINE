package dev.vineengine.vine.internal;

import dev.vineengine.vine.brain.VineBrain;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

/**
 * Internal bridge from {@code VineBrains} (vine-api) to vine-core's brain runtime.
 * NOT public API — implemented once by vine-core's engine object; never implemented
 * or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess}, so the engine's exactly-one-provider
 * rule, boot ordering and cached boot failure apply to brain calls unchanged (same
 * pattern as {@link VoxelBackend}, {@link WorldBackend} and {@link EntityBackend}).
 */
public interface BrainBackend {

    /** A brain for {@code actorId} whose memory is {@code blackboard}. */
    VineBrain brain(VineId actorId, VoxelData blackboard);
}
