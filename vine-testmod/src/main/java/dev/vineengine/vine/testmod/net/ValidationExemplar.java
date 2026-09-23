package dev.vineengine.vine.testmod.net;

import java.util.List;

import dev.vineengine.vine.net.Channel;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.VersionPolicy;
import dev.vineengine.vine.net.VineBuf;
import dev.vineengine.vine.net.VineNet;
import dev.vineengine.vine.net.Validators;
import dev.vineengine.vine.registry.VineId;

/**
 * Validation exemplar (sub-05 Stage D, sub-22 content contract): a rate-limited
 * C2S message whose handler reports the thread it ran on, plus a message whose
 * codec emits a hostile length prefix — proving the engine's bounds check drops
 * a fuzzed frame before any handler runs.
 */
public final class ValidationExemplar {

    /** The rate-limited payload: one sequence number. */
    public record FloodPacket(int seq) {
    }

    /** The fuzz payload: encodes a length prefix far beyond the buffer. */
    public record FuzzPacket(int ignored) {
    }

    /** The handler runs on the cell's main thread — this is what the TCK asserts. */
    public static final String SERVER_THREAD_MARKER = "validation handled";

    private static final VineId CHANNEL_ID = VineId.of("vine_test", "validation");

    private static final PayloadCodec<FloodPacket> FLOOD_CODEC = new PayloadCodec<>() {
        @Override
        public FloodPacket decode(VineBuf buf) {
            return new FloodPacket(buf.readVarInt());
        }

        @Override
        public void encode(VineBuf buf, FloodPacket value) {
            buf.writeVarInt(value.seq());
        }
    };

    /**
     * Deliberately malformed on the wire: the encoder emits bytes its own decoder
     * rejects (a list prefix beyond the declared bound), which is what a hostile
     * peer's frame looks like to the engine — the bounds check must drop it
     * before any handler runs. The engine never sees the encoder; the decoder is
     * the boundary.
     */
    private static final PayloadCodec<FuzzPacket> FUZZ_CODEC = new PayloadCodec<>() {
        @Override
        public FuzzPacket decode(VineBuf buf) {
            int claimed = buf.readVarInt();
            if (claimed > 8) {
                throw new dev.vineengine.vine.net.CodecException(
                    "fuzz: list size " + claimed + " outside [0, 8]");
            }
            return new FuzzPacket(claimed);
        }

        @Override
        public void encode(VineBuf buf, FuzzPacket value) {
            buf.writeVarInt(99);
        }
    };

    private ValidationExemplar() {
    }

    /** Registers the validation channel; call from consumer init. */
    public static void register() {
        VineNet net = VineNet.get();
        Channel channel = net.channel(new ChannelSpec(CHANNEL_ID, 1, VersionPolicy.OPTIONAL));
        channel.message(VineId.of("vine_test", "flood"), FloodPacket.class, FLOOD_CODEC,
            Endpoint.SERVER, List.of(Validators.rateLimit(2, 2)),
            (payload, context) -> System.out.println("vine-testmod: " + SERVER_THREAD_MARKER
                + " seq=" + payload.seq() + " thread=" + Thread.currentThread().getName()));
        channel.message(VineId.of("vine_test", "fuzz"), FuzzPacket.class, FUZZ_CODEC,
            Endpoint.SERVER, List.of(), (payload, context) ->
                System.out.println("vine-testmod: fuzz handler RAN (engine should have dropped the frame)"));
    }

    /** Command entry: floods the rate-limited message from this side. */
    public static void flood(int count) {
        Channel channel = VineNet.get().channel(
            new ChannelSpec(CHANNEL_ID, 1, VersionPolicy.OPTIONAL));
        for (int i = 1; i <= count; i++) {
            channel.sendToServer(new FloodPacket(i));
        }
        System.out.println("vine-testmod: validation flood sent=" + count);
    }

    /** Command entry: sends one deliberately malformed frame through the real path. */
    public static void fuzz() {
        Channel channel = VineNet.get().channel(
            new ChannelSpec(CHANNEL_ID, 1, VersionPolicy.OPTIONAL));
        channel.sendToServer(new FuzzPacket(0));
        System.out.println("vine-testmod: validation fuzz sent=true");
    }
}
