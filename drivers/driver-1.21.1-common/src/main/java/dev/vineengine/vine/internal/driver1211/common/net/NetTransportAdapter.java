package dev.vineengine.vine.internal.driver1211.common.net;

import java.util.List;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.net.NetTransport;
import dev.vineengine.vine.internal.net.NetTransportBinding;
import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.registry.VineId;

/**
 * The documented Stage A seam between a driver's {@link NetDriver} and vine-core's
 * {@link NetTransportBinding}: structurally identical interfaces, nominally
 * different ({@code NetTransport} lives in vine-core until its NetDriver bridge
 * lands). One adapter per process; exactly-one-bind is enforced by the core.
 * Deleted when vine-core consumes the SPI directly — drivers then touch only
 * {@code NetDriver}.
 */
public final class NetTransportAdapter {

    private NetTransportAdapter() {
    }

    /** Binds {@code driver} as the process transport; returns the engine's inbound sink. */
    public static NetTransport.InboundSink bind(NetDriver driver) {
        return NetTransportBinding.bind(new NetTransport() {
            @Override
            public void registerChannel(ChannelSpec spec, List<MessageSpec> messages) {
                driver.register(spec, messages.stream()
                    .map(message -> new NetDriver.MessageSpec(message.wireId(), message.handlerEndpoint()))
                    .toList());
            }

            @Override
            public void sendToClient(VinePlayer player, VineId wireId, byte[] payload) {
                driver.send(player, wireId, payload);
            }

            @Override
            public void sendToServer(VineId wireId, byte[] payload) {
                driver.sendToServer(wireId, payload);
            }

            @Override
            public boolean isReady(VinePlayer player) {
                return driver.isReady(player);
            }
        });
    }
}
