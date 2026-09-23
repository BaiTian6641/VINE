package dev.vineengine.vine.session;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.vineengine.vine.registry.VineId;

/**
 * One live session (sub-14 §2) — the server-authoritative view. Reads are
 * open to anything server-side; the only write is {@link #submit}, which the
 * session's {@link SessionRules} decide.
 */
public interface VineSession {

    /** Engine-assigned session identity. */
    VineId id();

    /** The registered session type this session instantiates. */
    VineId type();

    SessionScope scope();

    SessionState state();

    Optional<ParticipantState> participant(UUID player);

    Set<UUID> participants();

    /**
     * Submits an action; the session's rules return the final verdict.
     * Denied actions change nothing.
     */
    ActionVerdict submit(SessionAction action);
}
