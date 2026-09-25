package dev.vineengine.vine.brain;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.internal.BrainBackend;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.registry.VineId;

/**
 * Static entry point for brains (sub-08 Stage B). Mirror of {@code VineEntities} /
 * {@code VineWorlds}: the call is a thin facade over the engine singleton, so a
 * consumer never builds an engine-internal object by hand.
 *
 * <p>The blackboard is the tree you pass in — the same {@code VoxelData} tree the
 * storage layer persists — so a brain and its memory have exactly one identity, and
 * a reloaded actor resumes with what it knew.
 */
public final class VineBrains {

    private VineBrains() {
    }

    /**
     * A brain for {@code actorId} whose memory is {@code blackboard}.
     *
     * @throws IllegalStateException if the running engine does not provide brains
     *         (headless runtime, or vine-core is not the engine)
     */
    public static VineBrain of(VineId actorId, VoxelData blackboard) {
        return backend().brain(actorId, blackboard);
    }

    private static BrainBackend backend() {
        if (EngineAccess.get() instanceof BrainBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide brains (headless runtime, or vine-core is not the engine)");
    }
}
