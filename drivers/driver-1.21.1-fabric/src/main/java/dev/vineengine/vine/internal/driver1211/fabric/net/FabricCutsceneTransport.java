package dev.vineengine.vine.internal.driver1211.fabric.net;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.cutscene.CutsceneFrame;
import dev.vineengine.vine.cutscene.VineCutscenes;
import dev.vineengine.vine.internal.cutscene.CutsceneRuntime;
import dev.vineengine.vine.internal.driver1211.fabric.boot.Fabric1211Driver;
import dev.vineengine.vine.registry.VineId;

/**
 * The 1.21.1 Fabric transport for the engine's cutscene frames (sub-23's delivery half). The
 * engine evaluates one frame per tick and hands it to {@link CutsceneRuntime#sender}; who
 * receives it is this cell's job, because a frame names a viewer by id and only a cell can
 * turn that into a connection.
 *
 * <p><b>Routing is the address.</b> The engine addresses a frame to exactly one viewer, and the
 * cell sends it to exactly that viewer — {@code PlayerManager.getPlayer(uuid)}, the same lookup
 * the engine's other S2C paths use. Nobody else receives it, and nothing is broadcast: a
 * cutscene does not capture a server's other players (sub-23's explicit-viewer rule). Frames
 * are S2C only; a client never sends one, and this class has no client → server path.
 *
 * <p><b>This cell's own wire id.</b> Like the part delta, a cutscene frame is engine internals
 * with no {@code ChannelSpec} descriptor, so the cell owns the id ({@code vine:cutscene_frame})
 * and the opaque {@link VineFabricPayload} envelope its other S2C traffic uses. The payload type
 * is registered here because Fabric's {@code ServerPlayNetworking.send} refuses an unregistered
 * id, and the {@code client} entrypoint registers the matching receiver
 * ({@code VineCutsceneClient}) — a dedicated server never reaches that half.
 *
 * <p><b>The end is a message, not a silence.</b> The runtime's seam delivers frames; a cutscene
 * ending produces none, so a client that only ever saw frames would have to infer an ending from
 * the absence of traffic — and until it did, a title would sit on screen and the camera would
 * stay forced. This transport therefore remembers the viewers it actually delivered a frame to
 * and, on the first server tick where the runtime is no longer playing, sends each of them the
 * end marker ({@link CutsceneFramePayload#encodeEnd()}). The client also has a staleness
 * fallback for a transport that dies mid-cutscene (a client's own timescale, documented there);
 * the marker is the contract path and the fallback is the safety net.
 *
 * <p><b>The clock is not here.</b> This class never advances the cutscene: whose clock a
 * cinematic runs on is the runtime's decision (sub-23 wires it separately), and a transport that
 * ticked it would move the frame numbers the sub-23 scenario asserts.
 *
 * <p><b>Invariants:</b> installed once, from this cell's driver bootstrap, before any world can
 * play a cutscene; the wire id is this class's alone; nothing here reads or writes engine
 * cutscene state beyond asking whether one is playing.
 */
public final class FabricCutsceneTransport {

    private static final Logger LOG = LoggerFactory.getLogger(FabricCutsceneTransport.class);

    /** This cell's wire id for one frame (the id the NeoForge cell registers too). */
    private static final VineId WIRE_ID = VineId.of("vine", "cutscene_frame");

    /** One install per process: a second payload-type registration would be a bug, not a retry. */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** The viewers frames actually reached — the ones an end marker still has to reach. */
    private static final Set<UUID> DELIVERED = ConcurrentHashMap.newKeySet();

    private FabricCutsceneTransport() {
    }

    /**
     * Binds the frame transport, once, from the driver's bootstrap: the S2C payload type
     * (Fabric's registration moment is mod init, and the send path demands it) and the
     * runtime's sender. A second call is a no-op.
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        CustomPayload.Id<VineFabricPayload> id = payloadId();
        PacketCodec<PacketByteBuf, VineFabricPayload> codec = PacketCodec.of(
            (payload, buf) -> buf.writeByteArray(payload.data()),
            buf -> new VineFabricPayload(id, buf.readByteArray()));
        PayloadTypeRegistry.playS2C().register(id, codec);
        CutsceneRuntime.sender(FabricCutsceneTransport::sendFrame);
        LOG.info("[VINE] cutscenes: frame transport bound to wire id {} (Fabric, S2C)", WIRE_ID);
    }

    /** This cell's payload id for a frame — what the client receiver registers against. */
    public static CustomPayload.Id<VineFabricPayload> payloadId() {
        return FabricNetDriver.type(WIRE_ID);
    }

    /**
     * Sends one frame to the one viewer it is addressed to. A viewer who is not online has no
     * client to tell and is simply skipped — the engine's viewer set is a decision, not a
     * promise that the player is still connected.
     */
    private static void sendFrame(UUID viewer, VineId cutscene, CutsceneFrame frame) {
        MinecraftServer server = Fabric1211Driver.currentServer();
        if (server == null) {
            return;
        }
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(viewer);
        if (player == null) {
            return;
        }
        ServerPlayNetworking.send(player, new VineFabricPayload(payloadId(),
            CutsceneFramePayload.encode(cutscene, frame)));
        if (DELIVERED.add(viewer)) {
            // Once per viewer per cutscene run: the frame stream itself is counted on the
            // receiving side, where "arrived" is the fact worth asserting.
            LOG.info("[VINE] cutscenes: first frame of {} delivered to {} (viewer {})",
                cutscene, player.getGameProfile().getName(), viewer);
        }
    }

    /**
     * Tells every viewer this transport has delivered to that the cutscene is over. Called from
     * this cell's server tick; it reads the runtime's playing state and never advances it.
     */
    public static void tick(MinecraftServer server) {
        if (DELIVERED.isEmpty() || VineCutscenes.playing()) {
            return;
        }
        int sent = 0;
        for (UUID viewer : DELIVERED) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(viewer);
            if (player == null) {
                continue;
            }
            ServerPlayNetworking.send(player, new VineFabricPayload(payloadId(), CutsceneFramePayload.encodeEnd()));
            sent++;
        }
        LOG.info("[VINE] cutscenes: cutscene ended — end marker sent to {}/{} viewer(s) (Fabric)",
            sent, DELIVERED.size());
        DELIVERED.clear();
    }
}
