package dev.vineengine.vine.internal.driver1211.fabric.net;

import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.network.ServerPlayerEntity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.internal.driver1211.fabric.entity.VineEntity;
import dev.vineengine.vine.internal.entity.PartSync;
import dev.vineengine.vine.registry.VineId;

/**
 * The 1.21.1 Fabric transport for the engine's part-state deltas (sub-08 Stage D).
 * The engine encodes a delta and counts its bytes ({@link PartSync}); who receives it
 * is this cell's job, because only a cell knows which players are tracking the body.
 *
 * <p><b>Routing.</b> A delta names an engine ref, and the cell's live-actor table
 * resolves that to the native body; Fabric's {@link PlayerLookup#tracking} then answers
 * exactly the players the game is already sending that body to. Nobody tracking the
 * actor means nobody to tell — a hit on a beast in an empty chunk costs no packets.
 *
 * <p><b>This cell's own wire id and envelope.</b> The engine's channel table is for
 * messages consumers register; part state is engine internals with no descriptor, so
 * this cell owns the id ({@code vine:part_delta}) and the opaque
 * {@link VineFabricPayload} shape its other S2C traffic already uses. S2C only: a
 * client never sends part state, and the type is registered here because Fabric's
 * {@code ServerPlayNetworking.send} refuses an unregistered payload id.
 *
 * <p><b>The actor rides the envelope.</b> The engine's delta is one actor's state, and
 * its own layout says the actor is the envelope's business — the engine has no native
 * entity id to name. So this cell puts the body's native id in front of the delta as a
 * varint (the same envelope the NeoForge cell builds), and the engine's bytes follow,
 * untouched. The engine's byte count is still exactly its own payload: the envelope is
 * this cell's, and it is not charged to the part-state budget.
 *
 * <p><b>Documented gap (client half).</b> This stage installs the sending half only: no
 * client-side receiver is registered for {@code vine:part_delta} on this cell, because a
 * receiver would live in the client-dist wiring ({@code VineFabricClient} /
 * {@code FabricClientNetwork}), which this stage's file set does not touch, and the
 * engine has no client-side part-state store to apply a decoded delta to yet. A
 * Fabric client that has the type registered but no receiver drops the payload
 * silently (the loader logs nothing and throws nothing), so shipping this half cannot
 * disturb a client — but nothing on a Fabric client *reads* a part delta today.
 * (The NeoForge cell goes one step further and decodes into fresh states, dropping the
 * result, purely to prove the two sides' descriptors agree.)
 *
 * <p><b>Invariants:</b> installed once, from this cell's driver bootstrap; the wire id
 * and the payload id are this class's alone; nothing here reads or writes part state —
 * the engine's payload crosses untouched.
 */
public final class FabricPartTransport {

    private static final Logger LOG = LoggerFactory.getLogger(FabricPartTransport.class);

    /** This cell's wire id for the part delta (the same id the NeoForge cell registers). */
    private static final VineId WIRE_ID = VineId.of("vine", "part_delta");

    /** One install per process: a second payload-type registration would be a bug, not a retry. */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    private FabricPartTransport() {
    }

    /**
     * Binds the delta transport, once, from the driver's bootstrap: the S2C payload type
     * (Fabric's registration moment is mod init, and the send path demands it) and the
     * engine's sender. A second call is a no-op.
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        CustomPayload.Id<VineFabricPayload> id = FabricNetDriver.type(WIRE_ID);
        PacketCodec<PacketByteBuf, VineFabricPayload> codec = PacketCodec.of(
            (payload, buf) -> buf.writeByteArray(payload.data()),
            buf -> new VineFabricPayload(id, buf.readByteArray()));
        PayloadTypeRegistry.playS2C().register(id, codec);
        PartSync.sender(FabricPartTransport::sendToTracking);
        LOG.info("[VINE] parts: delta transport bound to wire id {} (Fabric, S2C)", WIRE_ID);
    }

    /**
     * Sends one actor's delta to every player tracking that actor's body. A ref with no
     * live body (removed or unloaded between the hit and the flush) has no trackers and
     * is simply not sent to — the actor's stored wound state is unaffected.
     */
    private static void sendToTracking(VineEntityRef ref, byte[] payload) {
        VineEntity actor = VineEntity.bodyFor(ref);
        if (actor == null) {
            return;
        }
        CustomPayload.Id<VineFabricPayload> id = FabricNetDriver.type(WIRE_ID);
        byte[] envelope = address(actor.getId(), payload);
        for (ServerPlayerEntity player : PlayerLookup.tracking(actor)) {
            ServerPlayNetworking.send(player, new VineFabricPayload(id, envelope));
        }
    }

    /**
     * The envelope: the actor's native entity id as a varint, then the engine's delta bytes
     * verbatim. Built once per delta (not once per player), and exactly sized — a delta is
     * already a rare, small packet, so this is not a place to trade clarity for a buffer
     * pool.
     */
    private static byte[] address(int entityId, byte[] payload) {
        int idBytes = 1;
        for (int value = entityId >>> 7; value != 0; value >>>= 7) {
            idBytes++;
        }
        byte[] envelope = new byte[idBytes + payload.length];
        int value = entityId;
        for (int index = 0; index < idBytes; index++) {
            boolean more = index < idBytes - 1;
            envelope[index] = (byte) ((value & 0x7F) | (more ? 0x80 : 0));
            value >>>= 7;
        }
        System.arraycopy(payload, 0, envelope, idBytes, payload.length);
        return envelope;
    }
}
