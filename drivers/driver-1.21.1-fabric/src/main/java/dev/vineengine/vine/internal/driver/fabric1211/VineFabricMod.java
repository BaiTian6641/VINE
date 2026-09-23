package dev.vineengine.vine.internal.driver.fabric1211;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scaffold entrypoint for the 1.21.1 Fabric cell (sub-00 Stage B). Engine
 * wiring lands in sub-18; for now this only proves the cell boots.
 */
public final class VineFabricMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineFabricMod.class);

    @Override
    public void onInitialize() {
        LOGGER.info("VINE driver 1.21.1-fabric alive");
    }
}
