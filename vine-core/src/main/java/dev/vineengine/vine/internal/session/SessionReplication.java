package dev.vineengine.vine.internal.session;

import java.util.UUID;

import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.session.ParticipantState;
import dev.vineengine.vine.session.SessionState;
import dev.vineengine.vine.session.VineSession;

/**
 * Session replication (sub-14 Stage C): one snapshot shape for join, late join
 * and reconnect, encoded through the engine's own blob format so it travels the
 * sub-05 channel like every other engine payload.
 *
 * <p>Visibility follows §2: the session's public state (phase, ticks, objectives,
 * shared flags) goes to every participant, a participant's own state always goes
 * to them, and *other* participants' states travel only when the session type
 * opts in ({@link dev.vineengine.vine.session.SessionRules#publicParticipantState()}).
 * Owner-only is the default because private progress is the common case.
 *
 * <p>The snapshot is exposed through {@link VineSession#replicationSnapshot} as
 * well as sent on join, so a consumer — or the TCK — can verify exactly what a
 * client is told without running one.
 */
public final class SessionReplication {

    private static final System.Logger LOG = System.getLogger("vine.session");

    private SessionReplication() {
    }

    /** The snapshot blob {@code viewer} would receive for {@code session}. */
    public static byte[] encode(VineSession session, UUID viewer, boolean publicParticipantState) {
        SessionState state = session.state();
        VoxelData root = VineData.create(SNAPSHOT_SCHEMA);
        root.put("id", session.id().toString());
        root.put("type", session.type().toString());
        root.put("phase", state.phase().name());
        root.put("ticks", state.ticksRemaining());
        root.put("objectives", state.objectives().copy());
        root.put("sharedFlags", state.sharedFlags().copy());
        boolean selfIncluded = false;
        int othersIncluded = 0;
        VoxelData participants = VineData.create(SNAPSHOT_SCHEMA);
        java.util.Map<UUID, ParticipantState> states = participantStatesOf(session);
        for (java.util.Map.Entry<UUID, ParticipantState> entry : states.entrySet()) {
            boolean self = entry.getKey().equals(viewer);
            if (!self && !publicParticipantState) {
                continue;
            }
            String prefix = "p_" + entry.getKey().toString().replace("-", "");
            participants.put(prefix + ".contribution", entry.getValue().contribution().copy());
            participants.put(prefix + ".loadout", entry.getValue().loadout().copy());
            participants.put(prefix + ".progress", entry.getValue().progress().copy());
            if (self) {
                selfIncluded = true;
            } else {
                othersIncluded++;
            }
        }
        root.put("participants", participants);
        // Counts make the visibility rule observable in a log line (the TCK's
        // assertion target) without decoding a blob in the scenario layer.
        root.put("selfIncluded", selfIncluded ? 1 : 0);
        root.put("othersIncluded", othersIncluded);
        return VineData.encode(root);
    }

    /** Sends {@code player}'s snapshot over the engine's session channel. */
    public static void sendSnapshot(VineSession session, UUID player) {
        byte[] payload = session.replicationSnapshot(player);
        LOG.log(System.Logger.Level.INFO, "[VINE] session " + session.id() + " snapshot to " + player
            + " (" + payload.length + "B)");
        SNAPSHOT_SENDER.send(session.id(), player, payload);
    }

    /** The engine's transport for snapshots — installed by the engine bootstrap. */
    public interface SnapshotSender {
        void send(VineId sessionId, UUID player, byte[] payload);
    }

    private static volatile SnapshotSender SNAPSHOT_SENDER = (sessionId, player, payload) -> {
        // No transport bound (headless boot): the snapshot is still built and
        // logged, so the path is exercised — it simply has nowhere to go.
    };

    public static void sender(SnapshotSender sender) {
        SNAPSHOT_SENDER = sender == null ? (sessionId, player, payload) -> {
        } : sender;
    }

    private static java.util.Map<UUID, ParticipantState> participantStatesOf(VineSession session) {
        return session instanceof ParticipantStates states ? states.participantStates() : java.util.Map.of();
    }

    /** What the service's session implementation offers the replication path. */
    interface ParticipantStates {
        java.util.Map<UUID, ParticipantState> participantStates();
    }

    static final VineId SNAPSHOT_SCHEMA = VineId.of("vine", "session_repl");

    private static boolean schemaRegistered;

    /** Registers the snapshot schema — called with the store schema at boot. */
    static synchronized void ensureSchema() {
        if (schemaRegistered) {
            return;
        }
        VineData.registerSchema(new dev.vineengine.vine.data.VoxelSchema(SNAPSHOT_SCHEMA, 1,
            com.mojang.serialization.Codec.unit(null)), java.util.List.of());
        schemaRegistered = true;
    }
}
