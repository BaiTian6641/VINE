package dev.vineengine.vine.internal.driver1211.neoforge.net;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.cutscene.CutsceneFrame;
import dev.vineengine.vine.cutscene.VineCutscenes;
import dev.vineengine.vine.internal.cutscene.CutsceneRuntime;
import dev.vineengine.vine.internal.driver1211.neoforge.client.VineCutsceneClient;
import dev.vineengine.vine.registry.VineId;

/**
 * The 1.21.1 NeoForge transport for the engine's cutscene frames (sub-23's delivery half) —
 * the Fabric cell's {@code FabricCutsceneTransport} on this loader. The engine evaluates one
 * frame per tick and hands it to {@link CutsceneRuntime#sender}; who receives it is this
 * cell's job, because a frame names a viewer by id and only a cell can turn that into a
 * connection.
 *
 * <p><b>Routing is the address.</b> The engine addresses a frame to exactly one viewer, and the
 * cell sends it to exactly that viewer — {@code PlayerList.getPlayer(uuid)}, the same lookup the
 * engine's other S2C paths use ({@code NeoForgeNetDriver.send}). Nobody else receives it, and
 * nothing is broadcast: a cutscene does not capture a server's other players (sub-23's
 * explicit-viewer rule). Frames are S2C only; a client never sends one, and this class has no
 * client → server path.
 *
 * <p><b>This cell's own wire id.</b> Like the part delta, a cutscene frame is engine internals
 * with no {@code ChannelSpec} descriptor, so the cell owns the id
 * ({@code vine:cutscene_frame}) and the opaque {@link VineNeoForgePayload} envelope its other
 * S2C traffic uses. The payload type is registered here ({@link #bindNative}) because NeoForge
 * refuses to send or accept an unregistered type; on NeoForge that one registration also
 * carries the client handler, which is the loader difference from Fabric's separate
 * {@code PayloadTypeRegistry} + {@code ClientPlayNetworking} pair (itself the same
 * {@code RegisterPayloadHandlersEvent} pattern {@code NeoForgePartTransport} uses).
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
 * cinematic runs on is the runtime's decision (the scripted client run ticks it separately, and
 * a transport that ticked it would move the frame numbers the sub-23 scenario asserts).
 *
 * <p><b>Invariants:</b> installed once, from this cell's driver bootstrap, before any world can
 * play a cutscene; the wire id is this class's alone; nothing here reads or writes engine
 * cutscene state beyond asking whether one is playing.
 */
public final class NeoForgeCutsceneTransport {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeCutsceneTransport.class);

    /** This cell's wire id for one frame (the id the Fabric cell registers too). */
    private static final VineId WIRE_ID = VineId.of("vine", "cutscene_frame");

    /** The registrar version this payload is registered under (NeoForge's channel-version rule). */
    private static final String VERSION = "1";

    /** One install per process: a second sender install would be a bug, not a retry. */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** The viewers frames actually reached — the ones an end marker still has to reach. */
    private static final Set<UUID> DELIVERED = ConcurrentHashMap.newKeySet();

    /** The envelope for one frame, shared with the client handler (NeoForge binds type and codec at once). */
    private static final CustomPacketPayload.Type<VineNeoForgePayload> TYPE = NeoForgeNetDriver.type(WIRE_ID);

    private static final StreamCodec<RegistryFriendlyByteBuf, VineNeoForgePayload> CODEC = StreamCodec.of(
        (buf, payload) -> buf.writeByteArray(payload.data()),
        buf -> new VineNeoForgePayload(TYPE, buf.readByteArray()));

    private NeoForgeCutsceneTransport() {
    }

    /**
     * Binds the frame transport, once, from the driver's bootstrap: the runtime's sender. The
     * payload type's registration moment on NeoForge is {@link RegisterPayloadHandlersEvent}
     * ({@link #bindNative}), not mod construction. A second call is a no-op.
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        CutsceneRuntime.sender(NeoForgeCutsceneTransport::sendFrame);
        LOG.info("[VINE] cutscenes: frame transport bound to wire id {} (NeoForge, S2C)", WIRE_ID);
    }

    /**
     * Registers the frame envelope with NeoForge's payload machinery; wired to
     * {@link RegisterPayloadHandlersEvent} on the mod bus next to the engine's own channels, so
     * a client refuses to connect over a missing registration instead of silently receiving
     * nothing.
     */
    public static void bindNative(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).playToClient(TYPE, CODEC, NeoForgeCutsceneTransport::receive);
    }

    /**
     * Sends one frame to the one viewer it is addressed to. A viewer who is not online has no
     * client to tell and is simply skipped — the engine's viewer set is a decision, not a
     * promise that the player is still connected.
     */
    private static void sendFrame(UUID viewer, VineId cutscene, CutsceneFrame frame) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(viewer);
        if (player == null) {
            return;
        }
        PacketDistributor.sendToPlayer(player,
            new VineNeoForgePayload(TYPE, CutsceneFramePayload.encode(cutscene, frame)));
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
            ServerPlayer player = server.getPlayerList().getPlayer(viewer);
            if (player == null) {
                continue;
            }
            PacketDistributor.sendToPlayer(player,
                new VineNeoForgePayload(TYPE, CutsceneFramePayload.encodeEnd()));
            sent++;
        }
        LOG.info("[VINE] cutscenes: cutscene ended — end marker sent to {}/{} viewer(s) (NeoForge)",
            sent, DELIVERED.size());
        DELIVERED.clear();
    }

    /**
     * The client half of the payload handler: a clientbound frame can only arrive on a physical
     * client, which is where {@code VineCutsceneClient} — and every {@code net.minecraft.client}
     * type it touches — is loaded. NeoForge runs payload handlers on the main thread, so the
     * client class only has to park the decoded frame for its next tick to apply.
     */
    private static void receive(VineNeoForgePayload payload, IPayloadContext context) {
        VineCutsceneClient.receive(payload.data(), context.player().getGameProfile().getName());
    }
}
