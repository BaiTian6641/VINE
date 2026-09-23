package dev.vineengine.vine.testmod.net;

import java.util.List;
import java.util.UUID;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.net.Channel;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.CodecException;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.VersionPolicy;
import dev.vineengine.vine.net.VineBuf;
import dev.vineengine.vine.net.VineNet;
import dev.vineengine.vine.registry.VineId;

/**
 * Late-join sync + chunked transport exemplar (sub-05 Stage E, sub-22 content
 * contract): two sync sources whose delivery order the TCK asserts, and a bulk
 * message exercised at 64 KiB (direct), 3 MiB (chunked) and 5 MiB (rejected at
 * encode, never on the wire).
 */
public final class SyncChunkExemplar {

    /**
     * The sync snapshot payloads. Two distinct record types because the engine
     * maps one payload class to one message id per channel (sends resolve by
     * class) — a deliberate constraint, not an inconvenience to work around.
     */
    public record SyncA(String name, int seq) {
    }

    public record SyncB(String name, int seq) {
    }

    /** The bulk payload: one opaque array. */
    public record BulkPacket(byte[] payload) {
    }

    private static final VineId CHANNEL_ID = VineId.of("vine_test", "sync");

    private static final PayloadCodec<SyncA> SYNC_A_CODEC = new PayloadCodec<>() {
        @Override
        public SyncA decode(VineBuf buf) {
            return new SyncA(buf.readUtf(), buf.readVarInt());
        }

        @Override
        public void encode(VineBuf buf, SyncA value) {
            buf.writeUtf(value.name());
            buf.writeVarInt(value.seq());
        }
    };

    private static final PayloadCodec<SyncB> SYNC_B_CODEC = new PayloadCodec<>() {
        @Override
        public SyncB decode(VineBuf buf) {
            return new SyncB(buf.readUtf(), buf.readVarInt());
        }

        @Override
        public void encode(VineBuf buf, SyncB value) {
            buf.writeUtf(value.name());
            buf.writeVarInt(value.seq());
        }
    };

    private static final PayloadCodec<BulkPacket> BULK_CODEC = new PayloadCodec<>() {
        @Override
        public BulkPacket decode(VineBuf buf) {
            return new BulkPacket(buf.readBytes());
        }

        @Override
        public void encode(VineBuf buf, BulkPacket value) {
            buf.writeBytes(value.payload());
        }
    };

    /** A synthetic player for headless join tests (the loopback delivers to it). */
    public record TestPlayer(UUID uniqueId, String name) implements VinePlayer {
    }

    private SyncChunkExemplar() {
    }

    /** Registers the sync + bulk surface; call from consumer init. */
    public static void register() {
        Channel channel = VineNet.get().channel(new ChannelSpec(CHANNEL_ID, 1, VersionPolicy.OPTIONAL));
        // Receivers first, then the sync sources — the order the API documents.
        channel.message(VineId.of("vine_test", "data_a"), SyncA.class, SYNC_A_CODEC,
            Endpoint.CLIENT, (payload, context) -> System.out.println("vine-testmod: sync received name="
                + payload.name() + " seq=" + payload.seq()));
        channel.message(VineId.of("vine_test", "data_b"), SyncB.class, SYNC_B_CODEC,
            Endpoint.CLIENT, (payload, context) -> System.out.println("vine-testmod: sync received name="
                + payload.name() + " seq=" + payload.seq()));
        channel.message(VineId.of("vine_test", "bulk"), BulkPacket.class, BULK_CODEC,
            Endpoint.SERVER, List.of(), (payload, context) -> System.out.println(
                "vine-testmod: bulk received bytes=" + payload.payload().length));
        channel.sync(VineId.of("vine_test", "data_a"), SyncA.class, SYNC_A_CODEC, player -> {
            System.out.println("vine-testmod: sync produced a seq=1");
            return new SyncA("a", 1);
        });
        channel.sync(VineId.of("vine_test", "data_b"), SyncB.class, SYNC_B_CODEC, player -> {
            System.out.println("vine-testmod: sync produced b seq=2");
            return new SyncB("b", 2);
        });
    }

    /** Command entry: simulates a player join on the loopback transport. */
    public static void join() {
        VinePlayer player = new TestPlayer(UUID.nameUUIDFromBytes("vine-tck-join".getBytes()), "[tck-join]");
        VineNet.get().onPlayerJoin(player);
        System.out.println("vine-testmod: join delivered syncs=true");
    }

    /** Command entry: sends a bulk payload of {@code kib}KiB and reports the outcome. */
    public static void bulk(int kib) {
        Channel channel = VineNet.get().channel(new ChannelSpec(CHANNEL_ID, 1, VersionPolicy.OPTIONAL));
        byte[] payload = new byte[kib * 1024];
        java.util.Arrays.fill(payload, (byte) 7);
        try {
            channel.sendToServer(new BulkPacket(payload));
            System.out.println("vine-testmod: bulk sent bytes=" + payload.length);
        } catch (CodecException rejected) {
            System.out.println("vine-testmod: bulk oversized rejected=true");
        }
    }
}
