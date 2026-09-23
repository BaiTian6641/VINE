package dev.vineengine.vine.internal.driver.nf26;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scaffold entrypoint for the 26.x NeoForge cell (sub-00 Stage C). Engine
 * wiring lands in sub-19; for now this only proves the cell boots.
 */
@Mod("vine")
public final class VineMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineMod.class);

    public VineMod() {
        LOGGER.info("VINE driver 26.x-neoforge alive");
    }
}
