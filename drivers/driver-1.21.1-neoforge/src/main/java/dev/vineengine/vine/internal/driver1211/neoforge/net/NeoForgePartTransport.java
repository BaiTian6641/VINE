package dev.vineengine.vine.internal.driver1211.neoforge.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.internal.driver1211.neoforge.entity.VineEntity;
import dev.vineengine.vine.internal.entity.PartDelta;
import dev.vineengine.vine.internal.entity.PartSync;

/**
 * The 1.21.1 NeoForge cell's part-delta transport (sub-08 Stage D): the engine encodes a
 * part-state delta and counts its bytes, and this class is what actually puts it on the
 * wire — to the players tracking the actor's body, which is the only set of clients whose
 * copy of that body the delta is about.
 *
 * <p><b>Who owns what.</b> The delta's layout, its deadband and its byte budget are the
 * engine's ({@link PartDelta}, {@link PartSync}); how a delta reaches a client is this
 * cell's, because only the cell knows which native entity an engine actor is. The engine's
 * seam is one function ({@link PartSync#sender}), installed once from the cell's driver
 * bootstrap — a cell that installs nothing leaves the engine's default, which builds and
 * counts deltas and drops them, so a headless boot still measures the budget.
 *
 * <p><b>Resolution.</b> A live body is remembered by the engine instance it was spawned as,
 * put in when the actor's identity is minted ({@link #bind}) and taken out when the body
 * leaves the level ({@link #unbind}). Both happen on the server thread that owns the body,
 * and a delta for a body that is not remembered is dropped rather than guessed at: no other
 * body carries that actor's parts.
 *
 * <p><b>Invariants:</b> no native type leaves this class or the entity package it resolves
 * through; the engine never sees a {@link VinePartPayload}, and this class never re-encodes
 * what the engine encoded.
 */
public final class NeoForgePartTransport {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgePartTransport.class);

    /** One envelope for every actor's delta: the actor is named inside it, not by its type. */
    private static final CustomPacketPayload.Type<VinePartPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vine", "part_delta"));

    /** The registrar version this payload is registered under (NeoForge's channel-version rule). */
    private static final String VERSION = "1";

    private static final StreamCodec<RegistryFriendlyByteBuf, VinePartPayload> CODEC = StreamCodec.of(
        (buf, payload) -> {
            buf.writeVarInt(payload.entityId());
            buf.writeByteArray(payload.delta());
        },
        buf -> new VinePartPayload(TYPE, buf.readVarInt(), buf.readByteArray()));

    /** Every part-bearing body that is in a level right now, by the engine instance it was spawned as. */
    private static final Map<UUID, VineEntity> LIVE = new ConcurrentHashMap<>();

    private static volatile boolean installed;

    private NeoForgePartTransport() {
    }

    /**
     * Installs the engine's transport sink; called once from the cell's driver bootstrap,
     * before any world can tick an actor. Idempotent, so a second call cannot wrap a
     * previous install.
     */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        PartSync.sender(NeoForgePartTransport::send);
    }

    /**
     * Remembers {@code entity} as the body of its engine instance; called when the actor's
     * identity becomes known. A descriptor that declares no parts is not remembered, because
     * it can never have a delta.
     */
    public static void bind(VineEntity entity) {
        UUID instance = entity.vineInstance();
        if (instance == null || entity.vineDescriptor().parts().isEmpty()) {
            // Neither a body the engine has tagged nor a multipart descriptor: it can never
            // be the target of a delta, so it is not remembered.
            return;
        }
        LIVE.put(instance, entity);
    }

    /** Forgets the body of a removed or unloaded entity; a no-op for a body never bound. */
    public static void unbind(VineEntity entity) {
        UUID instance = entity.vineInstance();
        if (instance != null) {
            LIVE.remove(instance);
        }
    }

    /**
     * Registers the envelope with NeoForge's payload machinery; wired to
     * {@link RegisterPayloadHandlersEvent} on the mod bus next to the engine's own channels,
     * so a client refuses to connect over a missing registration instead of silently
     * receiving nothing.
     */
    public static void bindNative(RegisterPayloadHandlersEvent event) {
        event.registrar(VERSION).playToClient(TYPE, CODEC, NeoForgePartTransport::receive);
    }

    /** The engine's transport: one actor's delta goes to whoever tracks that actor's body. */
    private static void send(VineEntityRef ref, byte[] payload) {
        VineEntity entity = LIVE.get(ref.instance());
        if (entity == null) {
            // No live body (removed, unloaded, or an actor this cell never saw): the delta
            // has no clients to reach, so dropping it is the honest answer, not a fallback.
            return;
        }
        PacketDistributor.sendToPlayersTrackingEntity(entity, new VinePartPayload(TYPE, entity.getId(), payload));
    }

    /**
     * The client half: resolves the actor by the native id the server named and decodes the
     * delta against the parts this client's copy of the descriptor declares — one part per
     * declared name, in declaration order, which is the order the wire's indices use.
     *
     * <p><b>Why the decoded states are dropped.</b> The engine has no client-side part-state
     * store yet ({@link PartDelta#apply} takes a list and returns a list), and keeping one
     * here would be a driver-side second copy of state the engine owns — exactly what the
     * host's contract forbids. Decoding is still done, because it is what proves this
     * client's descriptor agrees with the sender's: a mismatch is refused and logged rather
     * than applied to the wrong part.
     */
    private static void receive(VinePartPayload payload, IPayloadContext context) {
        Entity nativeEntity = context.player().level().getEntity(payload.entityId());
        if (!(nativeEntity instanceof VineEntity entity)) {
            // A body this client does not have (already removed, or a delta that raced the
            // body's own tracking): there is nothing to apply it to.
            return;
        }
        List<PartState> parts = new ArrayList<>(entity.vineDescriptor().parts().size());
        for (PartDescriptor part : entity.vineDescriptor().parts()) {
            parts.add(PartState.fresh(part.name()));
        }
        try {
            List<PartState> decoded = PartDelta.apply(parts, payload.delta());
            LOG.debug("[VINE] parts: delta for {} decoded into {} part state(s) ({} bytes)",
                entity.vineId(), decoded.size(), payload.delta().length);
        } catch (RuntimeException e) {
            // A delta this client cannot read is a protocol disagreement, never a world
            // condition: refused loudly in the log, and never allowed to take the client
            // down inside a packet handler.
            LOG.warn("[VINE] parts: rejected delta for {} — {}", entity.vineId(), e.toString());
        }
    }
}
