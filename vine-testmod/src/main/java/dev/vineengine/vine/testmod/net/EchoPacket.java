package dev.vineengine.vine.testmod.net;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.vineengine.vine.net.Channel;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.VineBuf;
import dev.vineengine.vine.net.VineCodecs;
import dev.vineengine.vine.net.VineNet;
import dev.vineengine.vine.net.VersionPolicy;
import dev.vineengine.vine.registry.VineId;

/**
 * Canonical networking exemplar (sub-05 Stage A): one packet echo —
 * {@code vine_test:echo} carries an int+String record C2S, the server handler
 * sends it straight back S2C. Pure vine-api: no loader or game classes, no
 * version conditionals (the testmod purity gate enforces this).
 *
 * <p>Why this shape: the TCK packet-echo scenario
 * ({@code vine-tck/scenarios/packet_echo.json}) drives {@link #sendToServer}
 * and asserts {@link #trace()} observed {@code ["c2s-received", "s2c-received"]}
 * in order — proving the full C2S→handler→S2C→handler path on every cell with
 * only engine-visible types crossing the consumer boundary.
 *
 * <p><b>Invariants:</b> {@link #trace()} entries appear in causal order
 * (c2s before s2c) and each at most once per sent payload.
 */
public final class EchoPacket {

    /** The echo payload: the M0 int+String record per sub-05 §3 Stage A. */
    public record Payload(int number, String text) {
    }

    /** Hand-written stage-A codec; the record-composition builder is stage B. */
    private static final PayloadCodec<Payload> CODEC = new PayloadCodec<>() {
        @Override
        public Payload decode(VineBuf buf) {
            return new Payload(buf.read(VineCodecs.VAR_INT), buf.read(VineCodecs.UTF));
        }

        @Override
        public void encode(VineBuf buf, Payload value) {
            buf.write(VineCodecs.VAR_INT, value.number());
            buf.write(VineCodecs.UTF, value.text());
        }
    };

    // Engine content ids use the unified "vinetest" namespace (Main ruling, sub-00 wave);
    // the loader mod id stays VineTestmod.MOD_ID.
    private static final VineId CHANNEL_ID = VineId.of("vinetest", "echo");
    private static final List<String> TRACE = new CopyOnWriteArrayList<>();

    private static volatile Channel channel;

    private EchoPacket() {
    }

    /** Registers the channel and both echo handlers. Called once from the testmod init. */
    public static void register() {
        Channel registered = VineNet.get().channel(
            new ChannelSpec(CHANNEL_ID, 1, VersionPolicy.REQUIRE_MATCH));
        VineId echoId = VineId.of("vinetest", "echo");
        registered.message(echoId, Payload.class, CODEC, Endpoint.SERVER, (payload, ctx) -> {
            TRACE.add("c2s-received");
            registered.send(ctx.sender(), payload);
        });
        registered.message(echoId, Payload.class, CODEC, Endpoint.CLIENT,
            (payload, ctx) -> TRACE.add("s2c-received"));
        channel = registered;
    }

    /** Sends one payload to the server (C2S) — the TCK SendPacket step's engine entry. */
    public static void sendToServer(Payload payload) {
        Channel current = channel;
        if (current == null) {
            throw new IllegalStateException("EchoPacket.register() has not run");
        }
        current.sendToServer(payload);
    }

    /** The observed echo trace, in causal order. */
    public static List<String> trace() {
        return List.copyOf(TRACE);
    }
}
