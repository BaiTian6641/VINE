package dev.vineengine.vine.testmod.net;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.net.Channel;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.VersionPolicy;
import dev.vineengine.vine.net.VineBuf;
import dev.vineengine.vine.net.VineNet;
import dev.vineengine.vine.registry.VineId;

/**
 * Handshake exemplar (sub-05 Stage C, sub-22 content contract): two channels with
 * different version policies, driven through the negotiation seam the transports
 * call when a peer's hello arrives. Each case uses its own synthetic player so
 * per-connection state cannot leak between them.
 */
public final class HandshakeExemplar {

    /** Payload of the REQUIRE_MATCH channel. */
    public record ReqPing(int seq) {
    }

    /** Payload of the OPTIONAL channel. */
    public record OptPing(int seq) {
    }

    public static final VineId REQ_CHANNEL = VineId.of("vine_test", "req");
    public static final VineId OPT_CHANNEL = VineId.of("vine_test", "opt");
    public static final int SERVER_PROTOCOL = 2;

    private static final PayloadCodec<ReqPing> REQ_CODEC = codec(ReqPing::new);
    private static final PayloadCodec<OptPing> OPT_CODEC = codec(OptPing::new);

    private HandshakeExemplar() {
    }

    /** Registers both channels; call from consumer init. */
    public static void register() {
        Channel req = VineNet.get().channel(
            new ChannelSpec(REQ_CHANNEL, SERVER_PROTOCOL, VersionPolicy.REQUIRE_MATCH));
        req.message(VineId.of("vine_test", "ping"), ReqPing.class, REQ_CODEC,
            Endpoint.CLIENT, (payload, context) -> System.out.println(
                "vine-testmod: handshake req delivered seq=" + payload.seq()));
        Channel opt = VineNet.get().channel(
            new ChannelSpec(OPT_CHANNEL, SERVER_PROTOCOL, VersionPolicy.OPTIONAL));
        opt.message(VineId.of("vine_test", "ping"), OptPing.class, OPT_CODEC,
            Endpoint.CLIENT, (payload, context) -> System.out.println(
                "vine-testmod: handshake opt delivered seq=" + payload.seq()));
    }

    /** Matching versions: the connection is ready and both channels carry traffic. */
    public static void match() {
        VinePlayer player = player("match");
        VineNet.get().onHandshake(player, Map.of(REQ_CHANNEL, SERVER_PROTOCOL,
            OPT_CHANNEL, SERVER_PROTOCOL));
        channel(REQ_CHANNEL).send(player, new ReqPing(1));
        channel(OPT_CHANNEL).send(player, new OptPing(2));
        System.out.println("vine-testmod: handshake match done=true");
    }

    /** OPTIONAL mismatch: that channel is disabled, the rest keeps working. */
    public static void optionalMismatch() {
        VinePlayer player = player("optional");
        VineNet.get().onHandshake(player, Map.of(REQ_CHANNEL, SERVER_PROTOCOL, OPT_CHANNEL, 1));
        channel(REQ_CHANNEL).send(player, new ReqPing(3));
        channel(OPT_CHANNEL).send(player, new OptPing(4));
        System.out.println("vine-testmod: handshake optional done=true");
    }

    /** REQUIRE_MATCH mismatch: the peer is refused and nothing is sent to it. */
    public static void requireMismatch() {
        VinePlayer player = player("require");
        VineNet.get().onHandshake(player, Map.of(REQ_CHANNEL, 1, OPT_CHANNEL, SERVER_PROTOCOL));
        channel(REQ_CHANNEL).send(player, new ReqPing(5));
        System.out.println("vine-testmod: handshake require done=true");
    }

    /** A foreign client advertising unknown channels: a clean no-op. */
    public static void vanillaJoin() {
        VinePlayer player = player("vanilla");
        VineNet.get().onHandshake(player, Map.of(VineId.of("other", "channel"), 7));
        channel(REQ_CHANNEL).send(player, new ReqPing(6));
        System.out.println("vine-testmod: handshake vanilla done=true");
    }

    private static Channel channel(VineId id) {
        return VineNet.get().channel(new ChannelSpec(id, SERVER_PROTOCOL,
            id.equals(REQ_CHANNEL) ? VersionPolicy.REQUIRE_MATCH : VersionPolicy.OPTIONAL));
    }

    private static VinePlayer player(String tag) {
        return new SyncChunkExemplar.TestPlayer(
            UUID.nameUUIDFromBytes(("vine-tck-" + tag).getBytes()), "[tck-" + tag + "]");
    }

    /** One int payload codec used by both channels (distinct record types). */
    private static <T> PayloadCodec<T> codec(java.util.function.IntFunction<T> factory) {
        return new PayloadCodec<>() {
            @Override
            public T decode(VineBuf buf) {
                return factory.apply(buf.readVarInt());
            }

            @Override
            public void encode(VineBuf buf, T value) {
                int seq = value instanceof ReqPing req ? req.seq() : ((OptPing) value).seq();
                buf.writeVarInt(seq);
            }
        };
    }

    /** Keeps the unused-import checker honest about List. */
    static List<VineId> channels() {
        return List.of(REQ_CHANNEL, OPT_CHANNEL);
    }
}
