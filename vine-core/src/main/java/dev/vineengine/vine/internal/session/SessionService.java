package dev.vineengine.vine.internal.session;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.internal.spi.WorldStoreSpi;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.session.ActionVerdict;
import dev.vineengine.vine.session.PartyRef;
import dev.vineengine.vine.session.ParticipantState;
import dev.vineengine.vine.session.SessionAction;
import dev.vineengine.vine.session.SessionContext;
import dev.vineengine.vine.session.SessionFactory;
import dev.vineengine.vine.session.SessionManager;
import dev.vineengine.vine.session.SessionPhase;
import dev.vineengine.vine.session.SessionRules;
import dev.vineengine.vine.session.SessionScope;
import dev.vineengine.vine.session.SessionState;
import dev.vineengine.vine.session.VineSession;

/**
 * vine-core's session service (sub-14 §2 internals): owns live sessions and
 * drives lifecycle transitions strictly through each session's
 * {@link SessionRules} — an action reaches state only via a verdict, and a
 * transition only via the engine's legal-transition table.
 *
 * <p><b>Phases:</b> {@code CREATED → ACTIVE → COMPLETED | ABANDONED}; illegal
 * transitions throw. Rules callbacks ({@code created}/{@code phaseAdvanced})
 * run with a read-only {@link SessionContext}; the rules are pure policy.
 *
 * <p><b>State trees:</b> each session type lazily owns schema
 * {@code vine:session/<ns>/<path>} (identity + version only, blobs route
 * through the engine's wire codec); objectives/sharedFlags trees are created
 * from it at session construction. Replication + persistence land in Stages
 * B/C.
 */
public final class SessionService implements SessionManager {

    private static final System.Logger LOG = System.getLogger("vine.session");

    private final Map<VineId, SessionFactory> factories = new LinkedHashMap<>();
    private final Map<VineId, EngineSession> live = new LinkedHashMap<>();
    private final AtomicLong sessionCounter = new AtomicLong();
    private volatile WorldStoreSpi store;
    private boolean frozen;

    /** Engine schema id backing a session type's state trees. */
    static VineId schemaIdFor(VineId sessionType) {
        return VineId.of("vine", "session/" + sessionType.namespace() + "/" + sessionType.path());
    }

    public synchronized void freeze() {
        frozen = true;
    }

    public synchronized void registerFactory(SessionFactory factory) {
        if (frozen) {
            throw new IllegalStateException("cannot register session factory " + factory.sessionType()
                + " — registration closed at REGISTRIES_FROZEN");
        }
        if (factories.containsKey(factory.sessionType())) {
            throw new IllegalStateException(
                "session type " + factory.sessionType() + " already has a factory");
        }
        factories.put(factory.sessionType(), factory);
        VineData.registerSchema(
            new VoxelSchema(schemaIdFor(factory.sessionType()), 1, Codec.unit(null)), List.of());
    }

    // ------------------------------------------------------------------
    // SessionManager
    // ------------------------------------------------------------------

    @Override
    public synchronized VineSession create(VineId sessionType, SessionScope scope, VoxelData params) {
        SessionFactory factory = factories.get(sessionType);
        if (factory == null) {
            throw new IllegalArgumentException(
                "no session factory registered for type " + sessionType);
        }
        VineId id = VineId.of("vine", "session/" + sessionCounter.incrementAndGet());
        EngineSession session = new EngineSession(id, sessionType, scope, factory.newRules());
        live.put(id, session);
        session.rules.created(session.context());
        LOG.log(System.Logger.Level.INFO,
            "[VINE] session created " + id + " type=" + sessionType + " phase=CREATED");
        return session;
    }

    @Override
    public synchronized Optional<VineSession> get(VineId sessionId) {
        return Optional.ofNullable(live.get(sessionId));
    }

    @Override
    public synchronized void transition(VineId sessionId, SessionPhase next) {
        EngineSession session = live.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("no live session " + sessionId);
        }
        SessionPhase current = session.state.phase();
        boolean legal = switch (current) {
            case CREATED -> next == SessionPhase.ACTIVE || next == SessionPhase.ABANDONED;
            case ACTIVE -> next == SessionPhase.COMPLETED || next == SessionPhase.ABANDONED;
            case COMPLETED, ABANDONED -> false;
        };
        if (!legal) {
            throw new IllegalStateException(
                "illegal session transition " + sessionId + ": " + current + " -> " + next);
        }
        session.state = new SessionState(next, session.state.ticksRemaining(),
            session.state.objectives(), session.state.sharedFlags());
        session.rules.phaseAdvanced(session.context(), next);
        LOG.log(System.Logger.Level.INFO, "[VINE] session " + sessionId + " phase " + next);
    }

    /** The live session count — used by acceptance harnesses and the TCK. */
    public synchronized int liveCount() {
        return live.size();
    }

    // ------------------------------------------------------------------
    // Persistence (sub-14 Stage B)
    // ------------------------------------------------------------------

    @Override
    public synchronized java.util.Set<VineId> liveSessions() {
        return java.util.Set.copyOf(live.keySet());
    }

    @Override
    public synchronized byte[] snapshot() {
        ensureStoreSchema();
        VoxelData store = VineData.create(STORE_SCHEMA);
        int index = 0;
        for (EngineSession session : live.values()) {
            String prefix = "sessions." + index++;
            SessionState state = session.state();
            store.put(prefix + ".id", session.id().toString());
            store.put(prefix + ".type", session.type().toString());
            store.put(prefix + ".scopeKind", session.scope().kind().name());
            store.put(prefix + ".scopeId", scopeId(session.scope()));
            store.put(prefix + ".phase", state.phase().name());
            store.put(prefix + ".ticksRemaining", state.ticksRemaining());
            store.put(prefix + ".objectives", state.objectives());
            store.put(prefix + ".sharedFlags", state.sharedFlags());
        }
        store.put("count", index);
        return VineData.encode(store);
    }

    @Override
    public synchronized int restore(byte[] blob) {
        if (blob == null || blob.length == 0) {
            return 0;
        }
        VoxelData store = VineData.decode(blob);
        int count = store.getInt("count");
        int restored = 0;
        for (int index = 0; index < count; index++) {
            String prefix = "sessions." + index;
            if (!store.contains(prefix + ".id")) {
                continue;
            }
            VineId id = VineId.parse(store.getString(prefix + ".id"));
            VineId type = VineId.parse(store.getString(prefix + ".type"));
            SessionFactory factory = factories.get(type);
            if (factory == null) {
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] session " + id + " references unknown type " + type + " — skipped on restore");
                continue;
            }
            SessionScope scope = SessionScope.Kind.valueOf(store.getString(prefix + ".scopeKind"))
                    == SessionScope.Kind.PARTY
                ? new SessionScope.Party(new PartyRef(VineId.parse(store.getString(prefix + ".scopeId"))))
                : new SessionScope.World(VineId.parse(store.getString(prefix + ".scopeId")));
            EngineSession session = new EngineSession(id, type, scope, factory.newRules());
            session.state = new SessionState(
                SessionPhase.valueOf(store.getString(prefix + ".phase")),
                store.getLong(prefix + ".ticksRemaining"),
                store.getCompound(prefix + ".objectives").copy(),
                store.getCompound(prefix + ".sharedFlags").copy());
            live.put(id, session);
            restored++;
        }
        // Keep the id counter ahead of restored sessions: ids are never reused.
        for (VineId id : live.keySet()) {
            if ("vine".equals(id.namespace()) && id.path().startsWith("session/")) {
                try {
                    long suffix = Long.parseLong(id.path().substring("session/".length()));
                    sessionCounter.updateAndGet(current -> Math.max(current, suffix));
                } catch (NumberFormatException ignored) {
                    // Non-numeric session ids (consumer-created) never advance the counter.
                }
            }
        }
        LOG.log(System.Logger.Level.INFO, "[VINE] session store restored " + restored + " session(s)");
        return restored;
    }

    @Override
    public void flush() {
        WorldStoreSpi current = store;
        if (current == null) {
            return;
        }
        try {
            synchronized (this) {
                current.save(STORE_KEY, snapshot());
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] session flush failed (world save continues): " + e);
        }
    }

    /** Mounts the driver's world store and restores whatever it holds (world load). */
    public void mount(WorldStoreSpi spi) {
        this.store = java.util.Objects.requireNonNull(spi, "spi");
        restore(spi.load(STORE_KEY));
    }

    private static final String STORE_KEY = "sessions";

    private static String scopeId(SessionScope scope) {
        return switch (scope) {
            case SessionScope.World world -> world.worldId().toString();
            case SessionScope.Party party -> party.party().partyId().toString();
        };
    }

    /** Registers the store schema; must run before the schema registry freezes. */
    public static synchronized void ensureStoreSchema() {
        SessionReplication.ensureSchema();
        if (storeSchemaRegistered) {
            return;
        }
        VineData.registerSchema(new VoxelSchema(STORE_SCHEMA, 1, Codec.unit(null)), List.of());
        storeSchemaRegistered = true;
    }

    private static final VineId STORE_SCHEMA = VineId.of("vine", "session_store");
    private static boolean storeSchemaRegistered;

    // ------------------------------------------------------------------
    // EngineSession
    // ------------------------------------------------------------------

    private static final class EngineSession implements VineSession, SessionReplication.ParticipantStates {

        private final VineId id;
        private final VineId type;
        private final SessionScope scope;
        private final SessionRules rules;
        private final Map<UUID, ParticipantState> participants = new LinkedHashMap<>();
        private volatile SessionState state;

        EngineSession(VineId id, VineId type, SessionScope scope, SessionRules rules) {
            this.id = id;
            this.type = type;
            this.scope = scope;
            this.rules = rules;
            VineId schema = schemaIdFor(type);
            this.state = new SessionState(SessionPhase.CREATED, 0,
                VineData.create(schema), VineData.create(schema));
        }

        @Override
        public VineId id() {
            return id;
        }

        @Override
        public VineId type() {
            return type;
        }

        @Override
        public SessionScope scope() {
            return scope;
        }

        @Override
        public SessionState state() {
            return state;
        }

        @Override
        public Optional<ParticipantState> participant(UUID player) {
            synchronized (participants) {
                return Optional.ofNullable(participants.get(player));
            }
        }

        @Override
        public ParticipantState join(UUID player, VoxelData loadout) {
            java.util.Objects.requireNonNull(player, "player");
            VineId schema = schemaIdFor(type);
            ParticipantState joining = new ParticipantState(player,
                VineData.create(schema), loadout == null ? VineData.create(schema) : loadout.copy(),
                VineData.create(schema));
            boolean owner;
            synchronized (participants) {
                owner = participants.isEmpty();
                participants.put(player, joining);
            }
            rules.joined(context(), player);
            LOG.log(System.Logger.Level.INFO,
                "[VINE] session " + id + " participant joined " + player + (owner ? " (owner)" : ""));
            // Late join and re-join share one path: the joiner is sent the full
            // replication snapshot the moment they are a participant (sub-14 Stage C).
            SessionReplication.sendSnapshot(this, player);
            return joining;
        }

        @Override
        public boolean isOwner(UUID player) {
            synchronized (participants) {
                return !participants.isEmpty() && participants.keySet().iterator().next().equals(player);
            }
        }

        @Override
        public byte[] replicationSnapshot(UUID player) {
            return SessionReplication.encode(this, player, rules.participantStatesPublic());
        }

        @Override
        public Set<UUID> participants() {
            synchronized (participants) {
                return Set.copyOf(participants.keySet());
            }
        }

        @Override
        public ActionVerdict submit(SessionAction action) {
            SessionContext ctx = context();
            ActionVerdict verdict = rules.decide(action, ctx);
            LOG.log(System.Logger.Level.INFO, "[VINE] session " + id + " action " + action.kind()
                + " " + (verdict instanceof ActionVerdict.Allow ? "ALLOWED"
                        : "DENIED " + ((ActionVerdict.Deny) verdict).reason()));
            return verdict;
        }

        SessionContext context() {
            return new Context(this);
        }

        /** Read-only context handed to rules callbacks. */
        /** The live participant states, in join order (replication reads them). */
        @Override
        public Map<UUID, ParticipantState> participantStates() {
            synchronized (participants) {
                return Map.copyOf(participants);
            }
        }

        private record Context(EngineSession session) implements SessionContext {
            @Override
            public SessionState state() {
                return session.state();
            }

            @Override
            public Set<UUID> participants() {
                return session.participants();
            }
        }
    }
}
