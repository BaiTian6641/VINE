package dev.vineengine.vine.internal.net;

import java.util.List;
import java.util.concurrent.Executor;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.registry.VineId;

/**
 * The bytes-only transport seam between vine-core's channel machinery and a
 * cell driver (sub-05 §2 "Driver contract"). Drivers move opaque encoded bytes;
 * the codec DSL never crosses this boundary.
 *
 * <p><b>Seam status (sub-05 Stage A):</b> this interface mirrors the documented
 * {@code vine-spi} {@code NetDriver} surface one-for-one —
 * {@code register} → {@link #registerChannel}, {@code send} →
 * {@link #sendToClient}, {@code toServer} → {@link #sendToServer},
 * {@code InboundSink.accept} → {@link InboundSink#accept} — minus
 * {@code openControlChannel} (stage-C handshake). It lives in vine-core until
 * the vine-spi {@code NetDriver} addition lands, then moves there unchanged.
 *
 * <p><b>Invariants:</b> exactly one transport binds per process
 * ({@link NetTransportBinding}); {@link InboundSink#accept} may be invoked on
 * any thread (loader network threads differ per cell) — vine-core decodes there
 * and re-dispatches the handler through the supplied {@code mainThread}
 * executor, so handlers never run on a network thread (§4).
 */
public interface NetTransport {

    /**
     * Pushes one channel's registration to the driver: the spec plus one
     * {@link MessageSpec} per (message, handler-endpoint) pair. Called again
     * whenever the table changes pre-freeze, and once at {@code REGISTRIES_FROZEN}
     * with the final table; the driver binds its loader payload types from the
     * last call it can honor (NF {@code PayloadRegistrar} / Fabric
     * {@code PayloadTypeRegistry}).
     */
    void registerChannel(ChannelSpec spec, List<MessageSpec> messages);

    /** Sends encoded payload bytes to one player's client (S2C). */
    void sendToClient(VinePlayer player, VineId wireId, byte[] payload);

    /** Sends encoded payload bytes to the server (C2S). */
    void sendToServer(VineId wireId, byte[] payload);

    /**
     * Whether the connection to {@code player} can carry VINE traffic. Stage A:
     * transport-reported liveness; the negotiated per-channel handshake state
     * replaces this in stage C.
     */
    boolean isReady(VinePlayer player);

    /**
     * vine-core's inbound entry point, handed to the driver via
     * {@link NetTransportBinding#bind}.
     */
    interface InboundSink {

        /**
         * Delivers one received payload.
         *
         * @param wireId wire identity from {@link MessageSpec#wireId}
         * @param receiving the endpoint now receiving ({@link Endpoint#SERVER}
         *        for C2S arrivals, {@link Endpoint#CLIENT} for S2C)
         * @param from the connection the payload arrived on
         * @param payload opaque encoded bytes
         * @param mainThread executor that runs on the receiving side's main
         *        thread; vine-core dispatches the consumer handler through it
         */
        void accept(VineId wireId, Endpoint receiving, VinePlayer from, byte[] payload, Executor mainThread);
    }

    /**
     * One message's wire registration. A message with handlers on both
     * endpoints appears twice (bidirectional registration).
     *
     * @param wireId payload id the driver registers with the loader
     * @param handlerEndpoint side that receives this direction
     */
    record MessageSpec(VineId wireId, Endpoint handlerEndpoint) {
    }
}
