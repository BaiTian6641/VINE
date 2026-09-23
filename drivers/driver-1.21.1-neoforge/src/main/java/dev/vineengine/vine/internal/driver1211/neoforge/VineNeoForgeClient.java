package dev.vineengine.vine.internal.driver1211.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client init for the 1.21.1 NeoForge cell (§2 dist segregation): NeoForge loads
 * this class only on the client dist, so a dedicated server never loads it — the
 * marker below must never appear in a dedicated-server log (Stage A assertion).
 * Client surfaces activate from this path only; engine core stays dist-agnostic.
 */
@Mod(value = "vine", dist = Dist.CLIENT)
public final class VineNeoForgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineNeoForgeClient.class);

    public VineNeoForgeClient() {
        LOGGER.info("VINE driver 1.21.1-neoforge client init");
    }
}
