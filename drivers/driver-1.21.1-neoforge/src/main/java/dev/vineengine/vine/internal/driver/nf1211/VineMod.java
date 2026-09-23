package dev.vineengine.vine.internal.driver.nf1211;

import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Scaffold entrypoint for the 1.21.1 NeoForge cell (sub-00 Stage B). Engine
 * wiring lands in sub-18; for now this only proves the cell boots.
 */
@Mod("vine")
public final class VineMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineMod.class);

    public VineMod() {
        LOGGER.info("VINE driver 1.21.1-neoforge alive");
    }
}
