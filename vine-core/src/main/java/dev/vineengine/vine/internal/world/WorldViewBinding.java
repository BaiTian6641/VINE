package dev.vineengine.vine.internal.world;

import java.util.Objects;

import dev.vineengine.vine.internal.spi.WorldViewDriver;

/**
 * The core-side binding point for the process's single {@link WorldViewDriver}
 * (sub-07 Stage B). A driver binds once, during bootstrap, and every engine world
 * view routes through it — the mirror of {@code VoxelStorageBinding}'s
 * exactly-one-bind rule: a second bind is a conflicting-driver bug.
 *
 * <p>Unlike the storage seam there is nothing for the engine to install before a
 * driver binds: a world view is created per call, so an unbound process fails at
 * {@link #bound()} with an explicit message (headless runtime, or a cell that has
 * not bound its world view yet) instead of handing back a view that silently
 * answers nothing.
 */
public final class WorldViewBinding {

    private static volatile WorldViewDriver driver;

    private WorldViewBinding() {
    }

    /**
     * Binds the process world-view driver.
     *
     * @return the bound driver, for the caller's convenience
     * @throws IllegalStateException on a second bind
     */
    public static synchronized WorldViewDriver bind(WorldViewDriver candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (driver != null) {
            throw new IllegalStateException("WorldViewDriver already bound: " + driver.getClass().getName()
                + " (conflicting second: " + candidate.getClass().getName() + ")");
        }
        driver = candidate;
        return candidate;
    }

    /**
     * The bound driver.
     *
     * @throws IllegalStateException when no driver bound one — headless runtimes
     *         and cells without a world view reach this instead of a guess
     */
    public static WorldViewDriver bound() {
        WorldViewDriver current = driver;
        if (current == null) {
            throw new IllegalStateException("no WorldViewDriver is bound — world views need a cell driver "
                + "(headless runtimes have no world to view)");
        }
        return current;
    }

    /** The bound driver, or {@code null} — for probes that report the seam's state. */
    public static WorldViewDriver boundOrNull() {
        return driver;
    }
}
