package dev.vineengine.vine.session;

import java.util.Optional;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

/**
 * Server-side authority over every live session (sub-14 §2): create sessions,
 * look them up, and drive lifecycle transitions (validated by the engine:
 * {@code CREATED → ACTIVE → COMPLETED | ABANDONED}).
 */
public interface SessionManager {

    /**
     * Creates and starts a session of {@code sessionType} — the factory's
     * rules receive {@code onCreated}; phase starts at {@code CREATED}.
     *
     * @param params session parameters (consumer-created tree; may be empty)
     * @throws IllegalArgumentException when no factory is registered for the type
     */
    VineSession create(VineId sessionType, SessionScope scope, VoxelData params);

    Optional<VineSession> get(VineId sessionId);

    /**
     * Advances a session to {@code next}; illegal transitions are rejected
     * with {@link IllegalStateException}.
     */
    void transition(VineId sessionId, SessionPhase next);
}
