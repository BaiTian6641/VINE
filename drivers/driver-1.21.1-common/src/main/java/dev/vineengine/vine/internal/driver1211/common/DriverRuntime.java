package dev.vineengine.vine.internal.driver1211.common;

import dev.vineengine.vine.internal.driver1211.common.events.HookBus;

/**
 * Process-lifetime handle to the bound driver's runtime pieces, installed during
 * {@code bootstrap}. Exists so engine-side consumers (TCK, testmod) can reach the
 * driver-internal collector while sub-01's bus is pending; dissolves into
 * {@code VineEngine.events()} once sub-01 Stage B/C lands (documented seam).
 */
public final class DriverRuntime {

    private static volatile HookBus hooks;

    private DriverRuntime() {
    }

    /** Called once by the driver's {@code bootstrap}. */
    public static void install(HookBus hookBus) {
        hooks = hookBus;
    }

    /**
     * The driver-internal hook collector.
     *
     * @throws IllegalStateException before engine boot binds the driver
     */
    public static HookBus hooks() {
        HookBus current = hooks;
        if (current == null) {
            throw new IllegalStateException("VINE driver not booted yet — hooks() is valid after bootstrap");
        }
        return current;
    }
}
