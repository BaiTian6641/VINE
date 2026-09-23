package dev.vineengine.vine.session;

import java.util.Optional;
import java.util.Set;

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

    /** Ids of every live session, in creation order — the verification surface. */
    Set<VineId> liveSessions();

    /**
     * Serializes every live session to one self-describing {@code VoxelData}
     * blob (sub-14 Stage B): id, type, phase, ticks, objectives and shared
     * flags per session. Byte-stable for identical state; the golden-fixture
     * cross-cell check rides this.
     */
    byte[] snapshot();

    /**
     * Restores sessions from a {@link #snapshot()} blob (suspend-on-unload
     * policy: the session comes back in its persisted phase with its state
     * trees intact). Existing live sessions with the same id are replaced.
     *
     * @return the number of sessions restored
     */
    int restore(byte[] blob);

    /**
     * Persists live sessions through the mounted store (drivers mount one on
     * world load); a no-op when no store is mounted.
     */
    void flush();
}
