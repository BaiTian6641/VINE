package dev.vineengine.vine.session;

import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.SessionBackend;

/**
 * Static entry point for the session machinery (sub-14 §2): register session
 * types (factories), then create/query sessions through the engine.
 *
 * <p>Factory registration closes at {@code REGISTRIES_FROZEN}; session
 * creation and lifecycle stay open forever. Backed by vine-core's session
 * service through the same {@code EngineAccess} seam as its siblings.
 */
public final class VineSessions {

    private VineSessions() {
    }

    /**
     * Registers a session type's factory.
     *
     * @throws IllegalStateException on a duplicate type or after
     *         {@code REGISTRIES_FROZEN}
     */
    public static void registerFactory(SessionFactory factory) {
        backend().registerFactory(factory);
    }

    /** The engine's session manager (create/get/transition). */
    public static SessionManager manager() {
        return backend().manager();
    }

    private static SessionBackend backend() {
        if (EngineAccess.get() instanceof SessionBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "engine core does not implement the session backend — vine-core version mismatch");
    }
}
