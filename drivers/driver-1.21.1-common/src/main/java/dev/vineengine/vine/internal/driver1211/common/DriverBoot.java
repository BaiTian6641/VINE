package dev.vineengine.vine.internal.driver1211.common;

import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.internal.core.ConsumerInitializers;

/**
 * Driver entrypoint boot helper shared by both 1.21.1 cells:
 * <ol>
 *   <li>installs the {@link DriverLogBridge} so vine-core's {@code System.Logger}
 *       lines (phase transitions included) reach the loader's log — must precede
 *       the first engine touch;</li>
 *   <li>triggers the engine boot ({@link VineEngine#get()} — ServiceLoader binds
 *       exactly one {@code VineDriver}, whose {@code bootstrap} wires the loader
 *       lifecycle and advances to {@code REGISTRIES_OPEN});</li>
 *   <li>runs consumer {@code VineInitializer}s while {@code REGISTRIES_OPEN} is
 *       in effect on every cell — the sub-02 Stage B discovery contract: the
 *       driver entrypoint invokes them right after engine boot returns, and
 *       their registration calls are direct (no phase anchoring).</li>
 * </ol>
 */
public final class DriverBoot {

    private DriverBoot() {
    }

    /** Boots the engine and runs consumer initializers. Called once per entrypoint. */
    public static void boot() {
        DriverLogBridge.install();
        VineEngine.get();
        ConsumerInitializers.run();
    }
}
