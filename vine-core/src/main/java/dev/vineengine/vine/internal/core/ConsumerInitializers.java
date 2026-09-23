package dev.vineengine.vine.internal.core;

import java.util.ServiceLoader;

import dev.vineengine.vine.VineInitializer;

/**
 * Runs consumer {@link VineInitializer}s once per process (sub-02 Stage B).
 *
 * <p>Invoked by the driver entrypoint right after engine boot returns — never
 * from inside {@code EngineAccess.boot}, where the facade is deliberately blocked
 * (initializers call {@code VineRegistries}, which needs the booted engine).
 * Registration is then genuinely open: the phase machine sits in
 * {@code REGISTRIES_OPEN} on every cell.
 *
 * <p>An initializer failure propagates: half-registered content is a boot
 * failure, never a silent skip. Zero consumers ⇒ zero work (Minimal Footprint).
 */
public final class ConsumerInitializers {

    private static boolean ran;

    private ConsumerInitializers() {
    }

    /** Idempotent: only the first call loads and runs initializers. */
    public static synchronized void run() {
        if (ran) {
            return;
        }
        ran = true;
        for (VineInitializer initializer : ServiceLoader.load(VineInitializer.class)) {
            initializer.init();
        }
    }
}
