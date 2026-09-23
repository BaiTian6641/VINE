package dev.vineengine.vine.internal.driver1211.fabric;

import net.fabricmc.api.ClientModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client init for the 1.21.1 Fabric cell (§2 dist segregation): wired as the
 * {@code "client"} entrypoint in fabric.mod.json, so a dedicated server never
 * loads this class — the marker below must never appear in a dedicated-server
 * log (Stage A assertion). Client surfaces activate from this path only.
 */
public final class VineFabricClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineFabricClient.class);

    @Override
    public void onInitializeClient() {
        LOGGER.info("VINE driver 1.21.1-fabric client init");
    }
}
