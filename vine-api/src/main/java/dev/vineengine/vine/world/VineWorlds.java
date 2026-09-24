package dev.vineengine.vine.world;

import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.WorldBackend;
import dev.vineengine.vine.registry.VineId;

/**
 * Static entry point for engine world views (sub-07 Stage B). Mirror of the
 * {@code VineData}/VoxelStorage pattern: the view is a thin handle over whatever
 * cell is running, resolved through the engine singleton, so a consumer never
 * binds a driver itself and never sees a native type.
 *
 * <p>Calling any method boots the engine (same {@code ServiceLoader} seam as
 * {@link dev.vineengine.vine.VineEngine#get()}); a call from inside driver
 * bootstrap is rejected — drivers use {@code DriverContext} until boot completes.
 * A cell that cannot reach a world (headless runtime, no driver bound) fails
 * explicitly, never with a guess.
 */
public final class VineWorlds {

    /** The overworld's dimension id — the one world every cell always has. */
    public static final VineId OVERWORLD = VineId.of("minecraft", "overworld");

    private VineWorlds() {
    }

    /** A view over the overworld. */
    public static VineWorld overworld() {
        return of(OVERWORLD);
    }

    /**
     * A view over {@code dimensionId}.
     *
     * @throws IllegalStateException if the running cell cannot address worlds
     *         (headless runtime or a driver that did not bind a world view)
     */
    public static VineWorld of(VineId dimensionId) {
        return backend().world(dimensionId);
    }

    private static WorldBackend backend() {
        if (EngineAccess.get() instanceof WorldBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide world views (headless runtime, or vine-core is not the engine)");
    }
}
