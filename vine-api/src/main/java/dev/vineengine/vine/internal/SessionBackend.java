package dev.vineengine.vine.internal;

import dev.vineengine.vine.session.SessionFactory;
import dev.vineengine.vine.session.SessionManager;

/**
 * Internal bridge from {@code VineSessions} (vine-api) to vine-core's session
 * service. NOT public API — implemented once by vine-core's engine object.
 *
 * <p>Resolved through {@link EngineAccess} so the engine's exactly-one-provider
 * rule, boot ordering, and cached boot failure apply unchanged (same pattern
 * as {@link RegistryBackend}).
 */
public interface SessionBackend {

    /** See {@code VineSessions#registerFactory}. */
    void registerFactory(SessionFactory factory);

    /** See {@code VineSessions#manager}. */
    SessionManager manager();
}
