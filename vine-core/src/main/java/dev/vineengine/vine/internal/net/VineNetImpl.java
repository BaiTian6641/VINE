package dev.vineengine.vine.internal.net;

import java.util.Objects;
import java.util.concurrent.Executor;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.Channel;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.CodecException;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.MessageHandler;
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
    private volatile NetDriver transport;

    public VineNetImpl() {
        NetTransportBinding.engineSink(this::onInbound);
        NetTransportBinding.onBind(bound -> {
            transport = bound;
            registry.pushTo(bound);
        });
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
