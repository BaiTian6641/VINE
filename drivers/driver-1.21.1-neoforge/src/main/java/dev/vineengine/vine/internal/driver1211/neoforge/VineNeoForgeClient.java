package dev.vineengine.vine.internal.driver1211.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.neoforge.net.NeoForgeNetDriver;
import dev.vineengine.vine.internal.driver1211.neoforge.net.VineNeoForgePayload;

/**
 * Client init for the 1.21.1 NeoForge cell (§2 dist segregation): NeoForge loads
 * this class only on the client dist, so a dedicated server never loads it — the
 * marker below must never appear in a dedicated-server log (Stage A assertion).
 * Client surfaces activate from this path only; engine core stays dist-agnostic.
 *
 * <p>Wires the client half of networking (sub-05 Stage A): the C2S sender the
 * transport uses when engine code on a client calls {@code sendToServer}.
 */
@Mod(value = "vine", dist = Dist.CLIENT)
public final class VineNeoForgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineNeoForgeClient.class);

    public VineNeoForgeClient() {
        LOGGER.info("VINE driver 1.21.1-neoforge client init");
        NeoForgeNetDriver.clientSender((wireId, bytes) -> {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new VineNeoForgePayload(NeoForgeNetDriver.type(wireId), bytes));
            }
        });
    }
}
