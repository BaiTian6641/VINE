package dev.vineengine.vine.internal.driver1211.fabric;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.fabric.client.ClientScriptRunner;
import dev.vineengine.vine.internal.driver1211.fabric.client.VineEntityRenderers;
import dev.vineengine.vine.internal.driver1211.fabric.net.FabricNetDriver;

/**
 * Client init for the 1.21.1 Fabric cell (§2 dist segregation): wired as the
 * {@code "client"} entrypoint in fabric.mod.json, so a dedicated server never
 * loads this class — the marker below must never appear in a dedicated-server
 * log (Stage A assertion). Client surfaces activate from this path only.
 *
 * <p>Wires the client half of networking (sub-05 Stage A): the C2S sender and
 * the S2C receivers for the engine's CLIENT-endpoint messages. Then arms the
 * scripted client run (sub-21's client half) when the harness set
 * {@code -Dvine.tck.clientScript}: with the property absent this entrypoint
 * behaves exactly as it always has, and nothing auto-quits.
 */
public final class VineFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineFabricClient.class);

    @Override
    public void onInitializeClient() {
        LOGGER.info("VINE driver 1.21.1-fabric client init");
        FabricNetDriver.bindClientReceivers();
        // Client renderers for the engine's own entity types (sub-08 Stage A's client half):
        // without them the first engine entity in view kills the render thread.
        VineEntityRenderers.register();
        // A scripted run drives the client from the tick loop; arming it last keeps the
        // engine's networking wiring in place before any step can produce a packet.
        ClientScriptRunner.installIfRequested();
    }
}
