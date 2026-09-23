package dev.vineengine.vine.internal.driver1211.common;

import java.util.logging.Level;

import org.slf4j.Logger;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.internal.core.ConsumerInitializers;

/**
 * Driver entrypoint boot helper shared by both 1.21.1 cells: triggers the engine
 * boot ({@link VineEngine#get()} — ServiceLoader binds exactly one
 * {@code VineDriver}, whose {@code bootstrap} wires the loader lifecycle) and
 * bridges vine-core's phase-transition lines onto the loader's Log4J setup.
 *
 * <p>vine-core logs {@code [VINE] phase <NAME>} through {@code System.Logger} to
 * stay dependency-free; its JUL fallback backend is invisible to the loader's
 * log4j appenders (and under loader classloaders a {@code System.LoggerFinder}
 * service inside a mod jar is never resolved), so the driver performs the bridge
 * instead: one replaying {@code onPhase} subscription per phase logs the canonical
 * line via SLF4J. Replays make the lines appear exactly once and in ordinal order
 * no matter which loader lifecycle moment boot happens at. The JUL fallback is
 * silenced so the transition never double-logs on the raw console.
 *
 * <p>After boot returns, consumer {@code VineInitializer}s run (sub-02 Stage B) —
 * strictly outside {@code EngineAccess.boot}, where the facade is still blocked.
 * Registration is open ({@code REGISTRIES_OPEN} was entered during bootstrap), so
 * initializers can define types and register descriptors immediately.
 */
public final class DriverBoot {

    /** vine-core's phase logger name (mirrors {@code PhaseMachine.LOG_NAME}). */
    private static final String CORE_LOG_NAME = "vine.boot";

    private DriverBoot() {
    }

    /**
     * Boots the engine and installs the phase-line bridge. Called once from each
     * loader entrypoint ({@code @Mod} constructor / {@code ModInitializer}).
     *
     * @param log the entrypoint's SLF4J logger, used for the canonical phase lines
     */
    public static void boot(Logger log) {
        java.util.logging.Logger.getLogger(CORE_LOG_NAME).setLevel(Level.OFF);
        VineEngine engine = VineEngine.get();
        for (EnginePhase phase : EnginePhase.values()) {
            engine.onPhase(phase, change -> log.info("[VINE] phase {}", change.entered()));
        }
        ConsumerInitializers.run();
    }
}
