package dev.vineengine.vine.net;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.registry.VineId;

/**
 * One registered engine channel (sub-05 §2). Obtained from
 * {@link VineNet#channel(ChannelSpec)}; channels are descriptors, so the same
 * spec always returns the same instance.
 *
 * <p><b>Invariants:</b> registration ({@link #message}) closes when the engine
 * enters {@code REGISTRIES_FROZEN} — late registration is a bug and throws.
 * Sending before {@link VineNet#isReady(VinePlayer) isReady} is a no-op with a
 * single log line per channel, never an exception (§2), so early-boot traffic
 * degrades silently instead of crashing consumers.
 */
public interface Channel {

    /** The spec this channel was registered with. */
    ChannelSpec spec();

    /**
     * Registers a message on this channel: payload type, codec, the endpoint
     * whose handler receives it, and the handler. The same id may be registered
     * once per endpoint (request/response pairs); a second registration for the
     * same id and endpoint, or with a different type/codec, throws. Sends resolve
     * the message by payload class, so one payload type maps to one message id
     * per channel — reusing a type for a second id throws.
     * @param <P> payload type
     * @param id message id; wire identity is derived from the channel id
     * @param type payload class token
     * @param codec payload serializer; built once, cached, reused for every send
     * @param endpoint side the handler runs on
     * @param handler consumer handler
     * @throws IllegalStateException if registration is frozen or the registration conflicts
     */
    <P> void message(VineId id, Class<P> type, PayloadCodec<P> codec,
                     Endpoint endpoint, MessageHandler<P> handler);

    /**
     * Sends a payload to one player (S2C). No-op with one log line if the
     * channel is not ready for that connection.
     *
     * @throws IllegalArgumentException if {@code payload}'s type is not a
     *         registered message on this channel
     */
    <P> void send(VinePlayer to, P payload);

    /**
     * Sends a payload to the server (C2S). No-op with one log line when called
     * on a client that has no VINE-ready connection.
     *
     * @throws IllegalArgumentException if {@code payload}'s type is not a
     *         registered message on this channel
     */
    <P> void sendToServer(P payload);
}
