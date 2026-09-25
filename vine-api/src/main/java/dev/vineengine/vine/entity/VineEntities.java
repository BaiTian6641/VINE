package dev.vineengine.vine.entity;

import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.brain.VineBrain;
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
     * @return the new instance's engine identity, or empty when the cell refused
     *         (dimension not loaded, position outside the world)
     * @throws IllegalArgumentException if no entity descriptor is registered under
     *         {@code entityId} — spawning unregistered content is a caller bug
     * @throws IllegalStateException if the running cell cannot spawn entities
     */
    public static Optional<VineEntityRef> spawn(VineId entityId, VineWorld world, Vec3 position) {
        return backend().spawn(entityId, world, position);
    }

    /**
     * Attaches {@code brain} to the instance {@code ref} names (sub-08 Stage C). The
     * cell's per-tick actor callback then ticks it; nothing ticks on its own.
     *
     * @throws IllegalArgumentException if no live instance carries {@code ref}
     * @throws IllegalStateException if that instance already has a brain attached
     */
    public static void attach(VineEntityRef ref, VineBrain brain) {
        Objects.requireNonNull(ref, "ref");
        backend().attach(ref, Objects.requireNonNull(brain, "brain"));
    }

    /** Drops whatever is attached to {@code ref}; idempotent, so an entity removal may call it blindly. */
    public static void detach(VineEntityRef ref) {
        backend().detach(ref);
    }

    private static EntityBackend backend() {
        if (EngineAccess.get() instanceof EntityBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide entity calls (headless runtime, or vine-core is not the engine)");
    }
}
