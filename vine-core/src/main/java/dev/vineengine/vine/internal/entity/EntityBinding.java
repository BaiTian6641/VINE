package dev.vineengine.vine.internal.entity;

import java.util.Objects;

import dev.vineengine.vine.internal.spi.EntityDriver;

/**
 * The core-side binding point for the process's single {@link EntityDriver}
 * (sub-08 Stage A). A driver binds once, during bootstrap; every engine entity call
 * routes through it — the mirror of {@code WorldViewBinding} and
 * {@code VoxelStorageBinding}'s exactly-one-bind rule, where a second bind is a
 * conflicting-driver bug.
 *
 * <p>Nothing is installed before a driver binds: a call from a headless runtime
 * fails at {@link #bound()} with an explicit message instead of a guess.
 */
public final class EntityBinding {

    private static volatile EntityDriver driver;

    private EntityBinding() {
    }

    /**
     * Binds the process entity driver.
     *
     * @return the bound driver, for the caller's convenience
     * @throws IllegalStateException on a second bind
     */
    public static synchronized EntityDriver bind(EntityDriver candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (driver != null) {
            throw new IllegalStateException("EntityDriver already bound: " + driver.getClass().getName()
                + " (conflicting second: " + candidate.getClass().getName() + ")");
        }
        driver = candidate;
        return candidate;
    }

    /**
     * The bound driver.
     *
     * @throws IllegalStateException when no driver bound one — headless runtimes and
     *         cells without an entity path reach this instead of a guess
     */
    public static EntityDriver bound() {
        EntityDriver current = driver;
        if (current == null) {
            throw new IllegalStateException("no EntityDriver is bound — entity calls need a cell driver"
                + " (headless runtimes have no entities)");
        }
        return current;
    }

    /** The bound driver, or {@code null} — for probes that report the seam's state. */
    public static EntityDriver boundOrNull() {
        return driver;
    }
}
