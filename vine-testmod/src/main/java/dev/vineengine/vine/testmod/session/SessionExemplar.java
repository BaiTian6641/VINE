package dev.vineengine.vine.testmod.session;

import java.util.List;
import java.util.UUID;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.session.ActionVerdict;
import dev.vineengine.vine.session.SessionAction;
import dev.vineengine.vine.session.SessionContext;
import dev.vineengine.vine.session.SessionFactory;
import dev.vineengine.vine.session.SessionManager;
import dev.vineengine.vine.session.SessionPhase;
import dev.vineengine.vine.session.SessionRules;
import dev.vineengine.vine.session.SessionScope;
import dev.vineengine.vine.session.VineSession;
import dev.vineengine.vine.session.VineSessions;

/**
 * Session exemplar (sub-14, sub-22 content contract: one canonical exemplar
 * per surface): one session type {@code vine_test:hunt} with consumer rules
 * that deny a known-bad action and allow everything else — driving the real
 * engine lifecycle (create → deny → activate → allow → complete) with
 * engine-owned state trees.
 *
 * <p>Consumer-pure by construction: engine types only, identical sources on
 * every cell.
 */
public final class SessionExemplar {

    public static final VineId HUNT_TYPE = VineId.of("vine_test", "hunt");

    private static final VineId ILLEGAL_ACTION = VineId.of("vine_test", "illegal");
    private static final VineId PARAMS_SCHEMA = VineId.of("vine_test", "hunt_params");

    /** Rules: deny the known-bad action; allow the rest. Pure policy. */
    private static final class HuntRules extends SessionRules {
        @Override
        protected ActionVerdict validate(SessionAction action, SessionContext ctx) {
            if (action.kind().equals(ILLEGAL_ACTION)) {
                return ActionVerdict.deny("action not permitted in phase " + ctx.state().phase());
            }
            return ActionVerdict.ALLOW;
        }
    }

    private SessionExemplar() {
    }

    /** Registers params schema + factory at init (REGISTRIES_OPEN contract). */
    public static void register() {
        VineData.registerSchema(new VoxelSchema(PARAMS_SCHEMA, 1, Codec.unit(null)), List.of());
        VineSessions.registerFactory(new SessionFactory() {
            @Override
            public VineId sessionType() {
                return HUNT_TYPE;
            }

            @Override
            public SessionRules newRules() {
                return new HuntRules();
            }
        });
    }

    /**
     * The TCK proof (driven by {@code vine_test tck_sessions}): full lifecycle
     * through the engine's manager, one printed line per check.
     */
    public static void runProof() {
        SessionManager manager = VineSessions.manager();
        VoxelData params = VineData.create(PARAMS_SCHEMA);
        params.put("arena", "vine_test:arena");

        VineSession session = manager.create(HUNT_TYPE,
            new SessionScope.World(VineId.of("vine_test", "arena")), params);
        System.out.println("vine-testmod: session created phase=" + session.state().phase());

        UUID player = UUID.nameUUIDFromBytes("tck-hunter".getBytes());
        ActionVerdict denied = session.submit(new SessionAction(player, ILLEGAL_ACTION, params));
        if (!(denied instanceof ActionVerdict.Deny deny)) {
            throw new IllegalStateException("illegal action was not denied");
        }
        System.out.println("vine-testmod: session denied kind=vine_test:illegal");

        manager.transition(session.id(), SessionPhase.ACTIVE);
        System.out.println("vine-testmod: session active phase=" + session.state().phase());

        ActionVerdict allowed = session.submit(
            new SessionAction(player, VineId.of("vine_test", "advance"), params));
        if (!(allowed instanceof ActionVerdict.Allow)) {
            throw new IllegalStateException("valid action was not allowed");
        }
        System.out.println("vine-testmod: session allowed kind=vine_test:advance");

        session.state().objectives().put("score", 12);
        System.out.println("vine-testmod: session objectives score="
            + session.state().objectives().getInt("score"));

        manager.transition(session.id(), SessionPhase.COMPLETED);
        System.out.println("vine-testmod: session completed phase=" + session.state().phase());
    }
}
