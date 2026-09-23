package dev.vineengine.vine.internal.spi;

import java.util.List;
import java.util.concurrent.Executor;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.registry.VineId;

/**
 * The driver-side networking SPI (sub-05 §2 "Driver contract"): the bytes-only
 * transport a cell driver implements to move engine payloads. One per cell,
 * implemented only by VINE's own driver jars — the codec DSL never crosses this
 * boundary; drivers move opaque encoded bytes.
 *
 * <p>Exact mirror of vine-core's {@code NetTransport} seam minus
 * {@code openControlChannel} (stage-C handshake): {@code register} ↔
 * {@code registerChannel}, {@code send} ↔ {@code sendToClient}, sinks are
 * signature-identical. Until vine-core bridges this SPI to
 * {@code NetTransportBinding}, drivers implement both interfaces and bind
 * directly (documented seam, sub-05 Stage A).
 *
 * <p><b>Invariants:</b> {@link InboundSink#accept} may be invoked on any thread
 * (loader network threads differ per cell) — vine-core decodes there and
 * re-dispatches the consumer handler through the supplied {@code mainThread}
 * executor, so handlers never run on a network thread.
 */
public interface NetDriver {

    /**
     * Pushes one channel's registration to the driver: the spec plus one
     * {@link MessageSpec} per (message, handler-endpoint) pair. Called again
     * whenever the table changes pre-freeze, and once at {@code REGISTRIES_FROZEN}
     * with the final table; the driver binds its loader payload types from the
     * last call it can honor (NF {@code PayloadRegistrar} / Fabric
     * {@code PayloadTypeRegistry}); {@code ChannelSpec.protocol} maps to the
     * loader's registrar/payload version.
     */
    void register(ChannelSpec spec, List<MessageSpec> messages);

    /** Sends encoded payload bytes to one player's client (S2C). */
    void send(VinePlayer player, VineId wireId, byte[] payload);

    /** Sends encoded payload bytes to the server (C2S; client-side use only). */
    void sendToServer(VineId wireId, byte[] payload);

    /**
     * Whether the connection to {@code player} can carry VINE traffic. Stage A:
     * transport-reported liveness; the negotiated per-channel handshake state
     * replaces this in stage C.
     */
    boolean isReady(VinePlayer player);

    /**
     * vine-core's inbound entry point, handed to the driver at bind time — the
     * only path received payload bytes take into the engine.
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
     *        ({@link Endpoint#SERVER} → C2S/playToServer, {@link Endpoint#CLIENT}
     *        → S2C/playToClient)
     */
    record MessageSpec(VineId wireId, Endpoint handlerEndpoint) {
    }
}
