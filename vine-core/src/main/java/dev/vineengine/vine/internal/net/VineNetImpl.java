package dev.vineengine.vine.internal.net;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.Channel;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.CodecException;
import dev.vineengine.vine.net.Validator;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookEvents;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.MessageHandler;
import dev.vineengine.vine.net.NetBudget;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.VersionPolicy;
import dev.vineengine.vine.net.VineCodecs;
import dev.vineengine.vine.net.VineNet;
import dev.vineengine.vine.registry.VineId;

/**
 * vine-core's {@link VineNet}: channel registration over {@link ChannelRegistry}
 * and inbound dispatch off the bound {@link NetDriver}.
 *
 * <p><b>Inbound pipeline (§2/§4):</b> bytes arrive from the driver on an
 * arbitrary loader network thread; the payload is decoded there (codec bounds
 * make hostile input cheap to reject), then the consumer handler is
 * re-dispatched through the driver-supplied main-thread executor — handlers
 * never run on a network thread, and {@code NetContext.enqueue} rides the same
 * executor. Unknown wire ids, missing handlers, and codec failures drop with a
 * structured log line; a throwing handler is isolated to its message.
 *
 * <p><b>Stage A readiness:</b> {@link #isReady} reports transport liveness; the
 * negotiated per-connection handshake state replaces it in stage C.
 */
public final class VineNetImpl implements VineNet {

    private static final System.Logger LOG = System.getLogger("vine.net");

    private final ChannelRegistry registry = new ChannelRegistry();
    private final EventBus bus;
    private volatile NetDriver transport;

    public VineNetImpl(EventBus bus) {
        this.bus = java.util.Objects.requireNonNull(bus, "bus");
        NetTransportBinding.engineSink(this::onInbound);
        // Engine-owned chunk transport (sub-05 Stage E): frames are ordinary
        // registered messages, so every cell's driver moves them unchanged.
        channel(CHUNK_SPEC).message(VineId.of("chunk", "frame"), ChunkFrame.class, CHUNK_CODEC,
            Endpoint.SERVER, (frame, context) -> reassemble(frame, context.sender()));
        NetTransportBinding.onBind(bound -> {
            transport = bound;
            registry.pushTo(bound);
        });
    }

    // ------------------------------------------------------------------
    // Chunk transport (sub-05 Stage E)
    // ------------------------------------------------------------------

    private static final ChannelSpec CHUNK_SPEC =
        new ChannelSpec(VineId.of("vine", "chunk"), 1, VersionPolicy.REQUIRE_MATCH);

    /** One chunk frame: which message it belongs to, its index, and 16 KiB of bytes. */
    public record ChunkFrame(String target, int index, int count, byte[] data) {
    }

    private static final PayloadCodec<ChunkFrame> CHUNK_CODEC = VineCodecs.<ChunkFrame>record()
        .field("target", VineCodecs.UTF, ChunkFrame::target)
        .field("index", VineCodecs.VAR_INT, ChunkFrame::index)
        .field("count", VineCodecs.VAR_INT, ChunkFrame::count)
        .field("data", VineCodecs.BYTES, ChunkFrame::data)
        .build(values -> new ChunkFrame((String) values[0], (Integer) values[1],
            (Integer) values[2], (byte[]) values[3]));

    /** Per-player reassembly state, capped and time-boxed (§4). */
    private static final class Reassembly {

        final byte[][] frames;
        final long startedNanos = System.nanoTime();
        int received;

        Reassembly(int count) {
            this.frames = new byte[count][];
        }
    }

    private final Map<String, Reassembly> reassemblies = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Sends one payload, applying the size budget: at or below
     * {@link NetBudget#MAX_DIRECT_BYTES} it travels as a single frame; above it
     * (up to {@link NetBudget#MAX_CHUNKED_BYTES}) as {@code vine:chunk/frame}
     * messages; above the chunked cap it is rejected here — never on the wire.
     *
     * @param to {@code null} sends client-to-server (the loopback/transport
     *        knows the connection); otherwise the payload goes to that player
     */
    void sendPayload(NetDriver transport, VinePlayer to, VineId wireId, byte[] payload) {
        if (payload.length > NetBudget.MAX_CHUNKED_BYTES) {
            throw new CodecException("payload for " + wireId + " is " + payload.length
                + " bytes, over the " + NetBudget.MAX_CHUNKED_BYTES + "-byte chunked cap");
        }
        if (payload.length <= NetBudget.MAX_DIRECT_BYTES) {
            if (to == null) {
                transport.sendToServer(wireId, payload);
            } else {
                transport.send(to, wireId, payload);
            }
            return;
        }
        int count = (payload.length + NetBudget.CHUNK_FRAME_BYTES - 1) / NetBudget.CHUNK_FRAME_BYTES;
        MessageEntry chunkEntry = registry.byWireId(VineId.of("vine", "chunk/frame"));
        if (chunkEntry == null) {
            throw new CodecException("chunk transport is not registered — engine state corrupted");
        }
        for (int index = 0; index < count; index++) {
            int from = index * NetBudget.CHUNK_FRAME_BYTES;
            int toByte = Math.min(payload.length, from + NetBudget.CHUNK_FRAME_BYTES);
            byte[] slice = java.util.Arrays.copyOfRange(payload, from, toByte);
            byte[] framed = encodeEntry(chunkEntry,
                new ChunkFrame(wireId.toString(), index, count, slice));
            if (to == null) {
                transport.sendToServer(chunkEntry.wireId, framed);
            } else {
                transport.send(to, chunkEntry.wireId, framed);
            }
        }
    }

    private byte[] encodeEntry(MessageEntry entry, Object payload) {
        ByteArrayVineBuf buf = ByteArrayVineBuf.writable();
        entry.codec.encode(buf, payload);
        return buf.toByteArray();
    }

    /** Accumulates frames until a payload is complete, then dispatches it for real. */
    private void reassemble(ChunkFrame frame, VinePlayer sender) {
        String key = sender.uniqueId() + " " + frame.target();
        Reassembly state = reassemblies.computeIfAbsent(key, ignored -> new Reassembly(frame.count()));
        if (state.frames.length != frame.count()) {
            reassemblies.remove(key);
            LOG.log(System.Logger.Level.WARNING, "[VINE] chunk frame count changed for " + frame.target()
                + " — partial reassembly dropped");
            return;
        }
        if (System.nanoTime() - state.startedNanos
                > java.util.concurrent.TimeUnit.SECONDS.toNanos(NetBudget.REASSEMBLY_TIMEOUT_SECONDS)) {
            reassemblies.remove(key);
            LOG.log(System.Logger.Level.WARNING, "[VINE] chunk reassembly for " + frame.target()
                + " timed out — dropped");
            return;
        }
        if (state.frames[frame.index()] == null) {
            state.frames[frame.index()] = frame.data();
            state.received++;
        }
        int total = 0;
        for (byte[] slice : state.frames) {
            if (slice == null) {
                // Still incomplete: enforce the reassembly cap while waiting.
                if (total > NetBudget.MAX_REASSEMBLY_BYTES) {
                    reassemblies.remove(key);
                    LOG.log(System.Logger.Level.WARNING, "[VINE] chunk reassembly for " + frame.target()
                        + " exceeds the " + NetBudget.MAX_REASSEMBLY_BYTES + "-byte cap — dropped");
                }
                return;
            }
            total += slice.length;
        }
        reassemblies.remove(key);
        if (total > NetBudget.MAX_REASSEMBLY_BYTES) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] reassembled " + frame.target() + " is " + total
                + " bytes, over the " + NetBudget.MAX_REASSEMBLY_BYTES + "-byte cap — dropped");
            return;
        }
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream(total);
        for (byte[] slice : state.frames) {
            joined.write(slice, 0, slice.length);
        }
        try {
            onInbound(VineId.parse(frame.target()), Endpoint.SERVER, sender, joined.toByteArray(),
                Runnable::run);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] chunked dispatch failed for "
                + frame.target() + ": " + e);
        }
    }

    // ------------------------------------------------------------------
    // Handshake (sub-05 Stage C)
    // ------------------------------------------------------------------

    private static final ChannelSpec HANDSHAKE_SPEC =
        new ChannelSpec(VineId.of("vine", "handshake"), 1, VersionPolicy.REQUIRE_MATCH);

    /** One connection's negotiated state: refused, or ready with per-channel disables. */
    private static final class ConnectionState {

        final Set<VineId> disabled = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile boolean refused;
        volatile String reason = "";
    }

    private final Map<UUID, ConnectionState> connections = new java.util.concurrent.ConcurrentHashMap<>();

    private ConnectionState stateOf(VinePlayer player) {
        return connections.computeIfAbsent(player.uniqueId(), id -> new ConnectionState());
    }

    @Override
    public void onHandshake(VinePlayer player, Map<VineId, Integer> advertised) {
        ConnectionState state = stateOf(player);
        int known = 0;
        // Unknown ids are ignored, not errors: a peer advertising channels this
        // side never registered (a foreign or older client) is the vanilla-join
        // case, and it must stay a clean no-op.
        for (Map.Entry<VineId, Integer> entry : advertised.entrySet()) {
            ChannelImpl channel = registry.channel(entry.getKey());
            if (channel == null) {
                continue;
            }
            known++;
            ChannelSpec spec = channel.spec();
            if (entry.getValue() == spec.protocol()) {
                continue;
            }
            switch (spec.policy()) {
                case REQUIRE_MATCH -> {
                    state.refused = true;
                    state.reason = "channel " + spec.id() + " requires protocol " + spec.protocol()
                        + ", peer offered " + entry.getValue();
                    LOG.log(System.Logger.Level.WARNING,
                        "[VINE] handshake: refusing " + player.name() + " — " + state.reason);
                }
                case OPTIONAL -> {
                    state.disabled.add(spec.id());
                    LOG.log(System.Logger.Level.WARNING, "[VINE] handshake: channel " + spec.id()
                        + " disabled for " + player.name() + " (peer protocol " + entry.getValue()
                        + ", server " + spec.protocol() + ")");
                }
                case SERVER_AUTHORITATIVE -> LOG.log(System.Logger.Level.INFO,
                    "[VINE] handshake: channel " + spec.id() + " keeps the server protocol "
                        + spec.protocol() + " for " + player.name());
            }
        }
        if (state.refused) {
            connections.put(player.uniqueId(), state);
            return;
        }
        // Acknowledge with the server's view so a real client can gate its own sends.
        LOG.log(System.Logger.Level.INFO, "[VINE] handshake: " + player.name() + " ready ("
            + (known - state.disabled.size()) + " channel(s) negotiated, "
            + state.disabled.size() + " disabled)");
    }

    /** Whether VINE may send to {@code player} at all (vanilla-join peers stay ready). */
    boolean connectionRefused(VinePlayer player) {
        ConnectionState state = connections.get(player.uniqueId());
        return state != null && state.refused;
    }

    /** Whether {@code channel} is disabled for {@code player} by negotiation. */
    boolean channelDisabled(VinePlayer player, VineId channelId) {
        ConnectionState state = connections.get(player.uniqueId());
        return state != null && state.disabled.contains(channelId);
    }

    @Override
    public void onPlayerJoin(VinePlayer player) {
        NetDriver bound = transport;
        if (bound == null) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] player join before a transport bound — syncs skipped");
            return;
        }
        // Registration order is the delivery order (sub-05 Stage E): a consumer's
        // dependent snapshots arrive in the order it declared them.
        for (ChannelRegistry.SyncEntry sync : registry.syncsInOrder()) {
            Object snapshot;
            try {
                snapshot = sync.source().snapshot(player);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] sync source for " + sync.wireId() + " threw — snapshot skipped: " + e);
                continue;
            }
            try {
                sendPayload(bound, player, sync.wireId(), encodeEntry(
                    registry.byWireId(sync.wireId()), snapshot));
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] sync delivery for " + sync.wireId() + " failed: " + e);
            }
        }
    }

    @Override
    public void onPlayerLeave(VinePlayer player) {
        String prefix = player.uniqueId() + " ";
        reassemblies.keySet().removeIf(key -> key.startsWith(prefix));
        connections.remove(player.uniqueId());
    }

    @Override
    public Channel channel(ChannelSpec spec) {
        Objects.requireNonNull(spec, "spec");
        ChannelImpl channel = registry.channel(spec, this);
        syncChannel(channel);
        return channel;
    }

    @Override
    public boolean isReady(VinePlayer player) {
        Objects.requireNonNull(player, "player");
        NetDriver bound = transport;
        return bound != null && bound.isReady(player);
    }

    /** Called when the engine enters {@code REGISTRIES_FROZEN}: close registration, push the final table. */
    public void freezeAndSync() {
        registry.freeze();
        NetDriver bound = transport;
        if (bound != null) {
            registry.pushTo(bound);
        }
    }

    /**
     * Pushes one channel's current table to the bound transport. Called on
     * channel creation and every message registration; idempotent for the
     * driver ({@link NetDriver#register} contract).
     */
    void syncChannel(ChannelImpl channel) {
        NetDriver bound = transport;
        if (bound != null) {
            bound.register(channel.spec(), registry.messageSpecs(channel));
        }
    }

    /** The bound transport, or {@code null} while dormant (no driver / pre-bind). */
    NetDriver transport() {
        return transport;
    }


    private void onInbound(VineId wireId, Endpoint receiving, VinePlayer from,
                           byte[] payload, Executor mainThread) {
        // Sub-01 Stage C packetReceive hook: the engine's single inbound
        // dispatch, so every transport (real loaders and the TCK loopback)
        // posts the same pre-event before decode/handler work.
        bus.post(new HookEvents.PacketReceive(wireId.toString(), from.uniqueId().toString(), payload));
        MessageEntry entry = registry.byWireId(wireId);
        if (entry == null) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] dropping inbound payload with unknown wire id " + wireId);
            return;
        }
        MessageHandler<Object> handler = entry.handler(receiving);
        if (handler == null) {
            LOG.log(System.Logger.Level.WARNING,
                "[VINE] dropping inbound " + wireId + " — no " + receiving + " handler registered");
            return;
        }
        Object decoded;
        try {
            ByteArrayVineBuf buf = ByteArrayVineBuf.wrap(payload);
            decoded = entry.codec.decode(buf);
            if (!buf.fullyRead()) {
                throw new CodecException("trailing bytes after " + wireId + " payload");
            }
        } catch (CodecException e) {
            LOG.log(System.Logger.Level.WARNING,
                "[VINE] dropping malformed inbound " + wireId + ": " + e.getMessage());
            return;
        }
        NetContextImpl context = new NetContextImpl(from, mainThread);
        mainThread.execute(() -> {
            // Validation runs on the server thread with the handler (sub-05
            // Stage D): a hostile payload can never make a validator race the
            // world, and a rejection can never leave a half-executed handler.
            for (Validator<Object> validator : entry.validators()) {
                Validator.Verdict verdict;
                try {
                    verdict = validator.validate(decoded, from);
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "[VINE] validator threw for " + wireId
                        + " — payload dropped: " + e);
                    return;
                }
                if (verdict != Validator.Verdict.ACCEPT) {
                    if (verdict == Validator.Verdict.KICK) {
                        // Kick request: reported with its own marker; the player
                        // facade owns actual disconnection (documented seam).
                        LOG.log(System.Logger.Level.WARNING, "[VINE] inbound " + wireId
                            + " rejected (KICK requested) from " + from.name());
                    } else {
                        LOG.log(System.Logger.Level.WARNING, "[VINE] inbound " + wireId
                            + " rejected by validator (dropped) from " + from.name());
                    }
                    return;
                }
            }
            try {
                handler.handle(decoded, context);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.ERROR,
                    "[VINE] handler for " + wireId + " threw — message isolated: " + e);
            }
        });
    }

    /** {@link dev.vineengine.vine.net.NetContext} over the receiving side's main-thread executor. */
    private record NetContextImpl(VinePlayer sender, Executor mainThread)
        implements dev.vineengine.vine.net.NetContext {

        @Override
        public void enqueue(Runnable task) {
            mainThread.execute(task);
        }
    }
}
