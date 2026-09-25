package dev.vineengine.vine.entity;

import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.EntityBackend;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorld;

/**
 * Static entry point for engine entity calls (sub-08 Stage A). Mirror of
 * {@code VineWorlds}/{@code VineData}: the call is a thin facade over whatever cell
 * is running, resolved through the engine singleton, so a consumer never binds a
 * driver and never sees a native entity type.
 *
 * <p>Calling any method boots the engine (same {@code ServiceLoader} seam as
 * {@code VineEngine.get()}); a call from inside driver bootstrap is rejected —
 * drivers use {@code DriverContext} until boot completes. A headless runtime, or a
 * cell that has not bound an entity driver, fails explicitly rather than guessing.
 */
public final class VineEntities {

    private VineEntities() {
    }

    /**
     * Spawns one entity of {@code entityId} at {@code position} in {@code world}.
     *
     * @return {@code true} when the entity exists there afterwards; {@code false}
     *         when the cell refused (dimension not loaded, position outside the
     *         world)
     * @throws IllegalArgumentException if no entity descriptor is registered under
     *         {@code entityId} — spawning unregistered content is a caller bug
     * @throws IllegalStateException if the running cell cannot spawn entities
     */
    public static boolean spawn(VineId entityId, VineWorld world, Vec3 position) {
        return backend().spawn(entityId, world, position);
    }

    private static EntityBackend backend() {
        if (EngineAccess.get() instanceof EntityBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide entity calls (headless runtime, or vine-core is not the engine)");
    }
}
