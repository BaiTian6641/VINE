package dev.vineengine.vine.internal.net;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.registry.VineId;

/**
 * TCK-only loopback transport (sub-21 Stage B). When the JVM property
 * {@code vine.tck.loopback=true} is set — exclusively by the TCK's headless
 * server launches — {@link NetTransportBinding} wraps the real driver in this
 * decorator, which routes outbound sends straight back through the engine's
 * inbound sink instead of the loader network stack.
 *
 * <p>Why this exists: the packet-echo scenario must prove the full
 * encode → wire-id → decode → handler-dispatch → reply path on a headless
 * dedicated server, where no client connection exists (sub-05 §3 acceptance
 * says "green headless"). The loopback exercises every engine and driver
 * seam except the physical socket; registration still delegates to the real
 * driver so payload types bind exactly as in production.
 *
 * <p><b>Invariants:</b> never active unless the property is explicitly set —
 * a production server without the flag runs the undecorated driver (Minimal
 * Footprint). All loop frames run on the calling thread (direct executor);
 * the sink contract permits any thread.
 */
final class TckLoopbackDriver implements NetDriver {

    private static final VinePlayer LOOP_PLAYER = new VinePlayer() {
        private final UUID id = UUID.nameUUIDFromBytes("vine-tck-loopback".getBytes());

        @Override
        public UUID uniqueId() {
            return id;
        }

        @Override
        public String name() {
            return "[tck-loopback]";
        }
    };

    private final NetDriver delegate;
    private final NetDriver.InboundSink sink;
    private final Executor direct = Runnable::run;

    TckLoopbackDriver(NetDriver delegate, NetDriver.InboundSink sink) {
        this.delegate = delegate;
        this.sink = sink;
    }

    @Override
    public void register(ChannelSpec spec, List<MessageSpec> messages) {
        // Real registration: payload types must bind exactly as production.
        delegate.register(spec, messages);
    }

    @Override
    public void send(VinePlayer player, VineId wireId, byte[] payload) {
        // S2C: deliver as if the player's client had just received it.
        sink.accept(wireId, Endpoint.CLIENT, player, payload, direct);
    }

    @Override
    public void sendToServer(VineId wireId, byte[] payload) {
        // C2S: deliver as if LOOP_PLAYER's connection had just sent it.
        sink.accept(wireId, Endpoint.SERVER, LOOP_PLAYER, payload, direct);
    }

    @Override
    public boolean isReady(VinePlayer player) {
        // The loopback always carries traffic; the real transport's liveness
        // is irrelevant without a socket.
        return true;
    }
}
