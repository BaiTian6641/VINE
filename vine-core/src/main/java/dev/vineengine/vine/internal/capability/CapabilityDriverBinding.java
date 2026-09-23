package dev.vineengine.vine.internal.capability;

import java.util.Objects;

import dev.vineengine.vine.internal.spi.CapabilityDriver;

/**
 * The core-side binding point for the process's single {@link CapabilityDriver}
 * (sub-04 Stage C/D). Best-effort interop: with no driver bound the engine's
 * fallback store still serves every engine capability, and foreign queries
 * report empty — absence is a value, never an exception.
 */
public final class CapabilityDriverBinding {

    private static volatile CapabilityDriver driver;

    private CapabilityDriverBinding() {
    }

    /** Binds the cell's driver once; a second bind is a conflicting-driver bug. */
    public static synchronized void bind(CapabilityDriver candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (driver != null) {
            throw new IllegalStateException("CapabilityDriver already bound: " + driver.getClass().getName()
                + " (conflicting second: " + candidate.getClass().getName() + ")");
        }
        driver = candidate;
    }

    /** The bound driver, or {@code null} on a cell without interop. */
    public static CapabilityDriver bound() {
        return driver;
    }
}
