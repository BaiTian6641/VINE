package dev.vineengine.vine.session;

import dev.vineengine.vine.registry.VineId;

/**
 * A registered session type (sub-14 §2): identity plus a rules supplier. The
 * engine's session service owns lifecycle; it asks the factory for a fresh
 * {@link SessionRules} instance per session.
 *
 * <p><b>Deviation note (implementation):</b> the original sketch had
 * {@code create(scope, params)} return a full {@code VineSession}; the
 * service-owned shape here keeps lifecycle transitions and replication in the
 * engine (a consumer-implemented {@code VineSession} would move authority
 * into consumers — the opposite of §2's "server-only authority" rule).
 */
public interface SessionFactory {

    VineId sessionType();

    /** Fresh rules instance for a new session of this type. */
    SessionRules newRules();
}
