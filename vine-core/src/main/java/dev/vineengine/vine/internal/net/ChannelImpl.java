package dev.vineengine.vine.internal.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.Channel;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.MessageHandler;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.SyncSource;
import dev.vineengine.vine.net.Validator;
import dev.vineengine.vine.registry.VineId;

/**
 * vine-core's {@link Channel}: registration delegate to {@link ChannelRegistry}
 * plus the two send paths. Sends resolve the message by exact payload class,
 * encode once into a fresh {@link ByteArrayVineBuf}, and hand opaque bytes to
 * the bound transport.
 *
 * <p>Send-before-ready (no transport bound, or the connection is not VINE-ready)
 * is a no-op with one log line per channel (§2) — early-boot traffic degrades
 * silently instead of crashing consumers.
 */
final class ChannelImpl implements Channel {

    private static final System.Logger LOG = System.getLogger("vine.net");

    private final ChannelSpec spec;
    private final ChannelRegistry registry;
    private final VineNetImpl net;
    private final List<MessageEntry> messages = new ArrayList<>();
    private final Map<Class<?>, MessageEntry> byType = new HashMap<>();
    private volatile boolean loggedNotReady;

    ChannelImpl(ChannelSpec spec, ChannelRegistry registry, VineNetImpl net) {
        this.spec = Objects.requireNonNull(spec, "spec");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.net = Objects.requireNonNull(net, "net");
    }

    @Override
    public ChannelSpec spec() {
        return spec;
    }

    @Override
    public <P> void message(VineId id, Class<P> type, PayloadCodec<P> codec,
                            Endpoint endpoint, MessageHandler<P> handler) {
        registry.registerMessage(this, id, type, codec, endpoint, handler);
        net.syncChannel(this);
    }

    @Override
    public <P> void message(VineId id, Class<P> type, PayloadCodec<P> codec,
                            Endpoint endpoint, java.util.List<Validator<P>> validators,
                            MessageHandler<P> handler) {
        registry.registerMessage(this, id, type, codec, endpoint, validators, handler);
        net.syncChannel(this);
    }

    @Override
    public <P> void sync(VineId id, Class<P> type, PayloadCodec<P> codec, SyncSource<P> source) {
        Objects.requireNonNull(source, "source");
        // The snapshot is an ordinary S2C message. If the consumer already
        // registered its receiver for this id (the usual case — it wants a say in
        // what happens on arrival), the sync only adds the source; otherwise the
        // engine installs a no-op client receiver so the message is complete.
        VineId wireId = registry.wireIdOf(this, id);
        if (registry.byWireId(wireId) == null) {
            message(id, type, codec, Endpoint.CLIENT, (payload, context) -> { });
        }
        registry.registerSync(wireId, codec, source);
        net.syncChannel(this);
    }

    @Override
    public <P> void send(VinePlayer to, P payload) {
        Objects.requireNonNull(to, "to");
        MessageEntry entry = entryFor(payload);
        NetDriver transport = net.transport();
        if (transport == null || !transport.isReady(to)) {
            logNotReady();
            return;
        }
        if (net.connectionRefused(to)) {
            // Handshake refused this peer (sub-05 Stage C): nothing is sent, and
            // the refusal was already logged with its reason.
            return;
        }
        if (net.channelDisabled(to, spec.id())) {
            logNotReady();
            return;
        }
        net.sendPayload(transport, to, entry.wireId, encode(entry, payload));
    }

    @Override
    public <P> void sendToServer(P payload) {
        MessageEntry entry = entryFor(payload);
        NetDriver transport = net.transport();
        if (transport == null) {
            logNotReady();
            return;
        }
        net.sendPayload(transport, null, entry.wireId, encode(entry, payload));
    }

    void add(MessageEntry entry) {
        MessageEntry previous = byType.putIfAbsent(entry.type, entry);
        if (previous != null && previous != entry) {
            throw new IllegalStateException("payload type " + entry.type.getName()
                + " is already message " + previous.id + " on channel " + spec.id()
                + " — sends resolve by payload class, so one type maps to one message id per channel");
        }
        if (previous == null) {
            messages.add(entry);
        }
    }

    List<MessageEntry> messages() {
        return List.copyOf(messages);
    }

    private MessageEntry entryFor(Object payload) {
        Objects.requireNonNull(payload, "payload");
        MessageEntry entry = byType.get(payload.getClass());
        if (entry == null) {
            throw new IllegalArgumentException("payload type " + payload.getClass().getName()
                + " is not a registered message on channel " + spec.id());
        }
        return entry;
    }

    private byte[] encode(MessageEntry entry, Object payload) {
        ByteArrayVineBuf buf = ByteArrayVineBuf.writable();
        entry.codec.encode(buf, payload);
        return buf.encoded();
    }

    private void logNotReady() {
        if (!loggedNotReady) {
            loggedNotReady = true;
            LOG.log(System.Logger.Level.INFO,
                "[VINE] channel " + spec.id() + " send before ready — dropped (logged once)");
        }
    }
}
