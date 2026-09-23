package dev.vineengine.vine.testmod.session;

import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.session.SessionManager;
import dev.vineengine.vine.session.SessionPhase;
import dev.vineengine.vine.session.SessionScope;
import dev.vineengine.vine.session.VineSession;
import dev.vineengine.vine.session.VineSessions;

/**
 * Session-persistence exemplar (sub-14 Stage B): create an ACTIVE session with
 * objectives, flush it through the driver-mounted store, and — after a graceful
 * reboot — report what the store restored. One printed line per check for the
 * {@code vine_test:session_persistence} TCK scenario.
 */
public final class SessionPersistenceExemplar {

    private static final VineId PARAMS_SCHEMA = VineId.of("vine_test", "hunt_params");
    private static final VineId WORLD = VineId.of("vine_test", "overworld");

    private SessionPersistenceExemplar() {
    }

    /** Command entry: create → activate → score → flush. */
    public static void createAndFlush() {
        SessionManager manager = VineSessions.manager();
        VoxelData params = VineData.create(PARAMS_SCHEMA);
        VineSession session = manager.create(SessionExemplar.HUNT_TYPE,
            new SessionScope.World(WORLD), params);
        manager.transition(session.id(), SessionPhase.ACTIVE);
        session.state().objectives().put("score", 12);
        manager.flush();
        System.out.println("vine-testmod: persistence created id=" + session.id()
            + " phase=" + session.state().phase());
        System.out.println("vine-testmod: persistence flushed live=" + manager.liveSessions().size());
    }

    /** Command entry: report sessions the store restored after the reboot. */
    public static void reportRestored() {
        SessionManager manager = VineSessions.manager();
        System.out.println("vine-testmod: persistence restored live=" + manager.liveSessions().size());
        boolean activeWithScore = false;
        for (VineId id : manager.liveSessions()) {
            VineSession session = manager.get(id).orElseThrow();
            if (session.state().phase() == SessionPhase.ACTIVE
                    && session.state().objectives().getInt("score") == 12) {
                activeWithScore = true;
            }
            System.out.println("vine-testmod: persistence restored session id=" + id
                + " phase=" + session.state().phase()
                + " score=" + session.state().objectives().getInt("score"));
        }
        // One deterministic line: the restored-set iteration order is not part of
        // the contract, the restored state is.
        System.out.println("vine-testmod: persistence active-check score=12 present=" + activeWithScore);
    }
}
