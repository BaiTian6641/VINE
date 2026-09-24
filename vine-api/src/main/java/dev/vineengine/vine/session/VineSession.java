package dev.vineengine.vine.session;

import java.util.Optional;

import dev.vineengine.vine.data.VoxelData;
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
     * Adds (or re-adds) a participant (sub-14 Stage C). The first joiner owns the
     * session — owner-only participant state (§2) is delivered to them and, for
     * types that opt in through {@link SessionRules#publicParticipantState()}, to
     * everyone else too. Joining triggers the engine's replication snapshot for
     * that player, which is also the late-join path.
     *
     * @return the participant's state as the engine stored it
     */
    ParticipantState join(UUID player, VoxelData loadout);

    /** Whether {@code player} is the session owner (its first joiner). */
    boolean isOwner(UUID player);

    /**
     * Encodes the replication snapshot {@code player} receives — the payload the
     * engine sends on join and on change. Decoded with {@code VineData.decode},
     * it carries the session id/type/phase/ticks and the public trees, plus the
     * viewer's own participant state and any participant states the type made
     * public. Exposed so a consumer (or the TCK) can verify exactly what a client
     * is told without running one.
     */
    byte[] replicationSnapshot(UUID player);

    /**
     * Submits an action; the session's rules return the final verdict.
     * Denied actions change nothing.
     */
    ActionVerdict submit(SessionAction action);
}
