package dev.vineengine.vine.internal.driver.fabric26;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scaffold entrypoint for the 26.x Fabric cell (sub-00 Stage C). Engine wiring
 * lands in sub-19; for now this only proves the cell boots.
 */
public final class VineFabricMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineFabricMod.class);

    @Override
    public void onInitialize() {
        LOGGER.info("VINE driver 26.x-fabric alive");
    }
}
