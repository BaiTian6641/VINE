package dev.vineengine.vine.internal.driver1211.neoforge;

import java.nio.file.Path;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.neoforge.client.ClientScriptRunner;
import dev.vineengine.vine.internal.driver1211.neoforge.client.VineCutsceneClient;
import dev.vineengine.vine.internal.driver1211.neoforge.client.VineEntityRenderer;
import dev.vineengine.vine.internal.driver1211.neoforge.net.NeoForgeNetDriver;
import dev.vineengine.vine.internal.driver1211.neoforge.net.VineNeoForgePayload;
import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeContentMaterializer;

/**
 * Client init for the 1.21.1 NeoForge cell (§2 dist segregation): NeoForge loads
 * this class only on the client dist, so a dedicated server never loads it — the
 * marker below must never appear in a dedicated-server log (Stage A assertion).
 * Client surfaces activate from this path only; engine core stays dist-agnostic.
 *
 * <p>Wires the client half of networking (sub-05 Stage A): the C2S sender the
 * transport uses when engine code on a client calls {@code sendToServer}.
 *
 * <p>Wires the client half of entity materialization (sub-08 Stage A): every entity
 * type the common materializer registers gets a renderer, without which vanilla's
 * renderer crashes on the first spawned engine entity.
 */
@Mod(value = "vine", dist = Dist.CLIENT)
public final class VineNeoForgeClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineNeoForgeClient.class);

    public VineNeoForgeClient(IEventBus modEventBus) {
        LOGGER.info("VINE driver 1.21.1-neoforge client init");
        NeoForgeNetDriver.clientSender((wireId, bytes) -> {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(new VineNeoForgePayload(NeoForgeNetDriver.type(wireId), bytes));
            }
        });
        modEventBus.addListener(EntityRenderersEvent.RegisterRenderers.class, event -> {
            for (var entityType : NeoForgeContentMaterializer.entityTypes()) {
                event.registerEntityRenderer(entityType, VineEntityRenderer::new);
            }
        });
        // Cutscene application (sub-23's client half): the tick that applies the frames this
        // server addresses to this player. Armed before any script step can produce one — the
        // scripted run below triggers cutscenes the moment it starts.
        VineCutsceneClient.install();
        armClientScript();
    }

    /**
     * Arms the scripted client run (sub-21 Stage F) only when the harness names a script:
     * with the property absent a dev client is exactly the client it was before, which is
     * the whole point of keying off one property rather than a build-time flag.
     */
    private static void armClientScript() {
        String scriptPath = System.getProperty(ClientScriptRunner.PROPERTY);
        if (scriptPath == null || scriptPath.isBlank()) {
            return;
        }
        ClientScriptRunner runner = new ClientScriptRunner(Path.of(scriptPath));
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> runner.tick(Minecraft.getInstance()));
        LOGGER.info("vine-tck: client script run armed from {}", scriptPath);
    }
}
