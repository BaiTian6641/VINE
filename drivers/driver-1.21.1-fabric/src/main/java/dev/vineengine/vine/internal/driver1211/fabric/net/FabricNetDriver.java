package dev.vineengine.vine.internal.driver1211.fabric.net;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.driver1211.common.events.HookEvent;
import dev.vineengine.vine.internal.driver1211.common.net.ChannelTable;
import dev.vineengine.vine.internal.driver1211.common.net.DriverVinePlayer;
import dev.vineengine.vine.internal.driver1211.common.net.NetTransportAdapter;
import dev.vineengine.vine.internal.net.NetTransport;
import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.registry.VineId;

/**
 * The 1.21.1 Fabric {@link NetDriver} (sub-05 Stage A): engine wire ids map 1:1 to
 * Fabric payload ids over one opaque-bytes payload shape.
 *
 * <p>Binding model (loader difference absorbed): Fabric binds payload types and
 * receivers imperatively during mod init, so every engine push binds natively at
 * once — there is no one-shot registration event like NF's
 * {@code RegisterPayloadHandlersEvent}. The channel protocol has no Fabric-side
 * registrar to ride; version negotiation is the sub-05 stage-C handshake's job
 * (documented gap vs. NF, where the registrar version carries it). Binding is
 * idempotent per (wire id, direction) — the engine re-pushes the table as it
 * grows and once at {@code REGISTRIES_FROZEN}.
 *
 * <p>C2S sending and S2C receiving are client-dist paths: wired by
 * {@code VineFabricClient} via {@link #bindClientReceivers()} (this class never
 * references client-only types). On a dedicated server {@link #sendToServer} is a
 * documented no-op.
 */
public final class FabricNetDriver implements NetDriver {

    private static final Logger LOG = LoggerFactory.getLogger(FabricNetDriver.class);
    private static final Map<VineId, CustomPayload.Id<VineFabricPayload>> TYPES = new ConcurrentHashMap<>();

    private final ChannelTable table = new ChannelTable();
    private final Set<String> bound = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Consumer<HookEvent>> packetHook;
    private volatile NetTransport.InboundSink engineSink;
    private volatile MinecraftServer server;

    private static volatile FabricNetDriver instance;
    private static volatile BiConsumer<VineId, byte[]> clientSender;

    public FabricNetDriver(AtomicReference<Consumer<HookEvent>> packetHook) {
        this.packetHook = packetHook;
        instance = this;
    }

    /** The payload id for {@code wireId}, created once (shared with the client sender). */
    public static CustomPayload.Id<VineFabricPayload> type(VineId wireId) {
        return TYPES.computeIfAbsent(wireId, id -> new CustomPayload.Id<>(Identifier.of(id.toString())));
    }

    /** Binds this driver as the process transport; called once from bootstrap. */
    public void bindTransport() {
        engineSink = NetTransportAdapter.bind(this);
    }

    /** Called from {@code SERVER_STARTED}/{@code SERVER_STOPPING} to track the live server. */
    public void server(MinecraftServer current) {
        server = current;
    }

    @Override
    public void register(ChannelSpec spec, List<MessageSpec> messages) {
        table.put(spec, messages);
        bindNative(messages);
        LOG.debug("[VINE] net: channel {} table pushed ({} message registration(s) bound natively)",
            spec.id(), messages.size());
    }

    @Override
    public void send(VinePlayer player, VineId wireId, byte[] payload) {
        MinecraftServer current = server;
        if (current == null) {
            return;
        }
        ServerPlayerEntity target = current.getPlayerManager().getPlayer(player.uniqueId());
        if (target == null) {
            return;
        }
        ServerPlayNetworking.send(target, new VineFabricPayload(type(wireId), payload));
    }

    @Override
    public void sendToServer(VineId wireId, byte[] payload) {
        BiConsumer<VineId, byte[]> sender = clientSender;
        if (sender != null) {
            sender.accept(wireId, payload);
        }
        // No sender = dedicated server: C2S has no meaning here (Stage A no-op).
    }

    @Override
    public boolean isReady(VinePlayer player) {
        MinecraftServer current = server;
        return current != null && current.getPlayerManager().getPlayer(player.uniqueId()) != null;
    }

    /**
     * Client-dist wiring, called from {@code VineFabricClient} only: the C2S
     * sender plus S2C receivers for every CLIENT-endpoint message in the current
     * table. Runs after common init, so the table is complete for this session.
     */
    public static void bindClientReceivers() {
        clientSender = (wireId, bytes) ->
            FabricClientNetwork.sendToServer(new VineFabricPayload(type(wireId), bytes));
        FabricNetDriver driver = instance;
        if (driver == null) {
            throw new IllegalStateException("FabricNetDriver not bootstrapped — entrypoint wiring broken");
        }
        for (ChannelTable.Entry entry : driver.table.entries()) {
            for (MessageSpec message : entry.messages()) {
                if (message.handlerEndpoint() == Endpoint.CLIENT) {
                    CustomPayload.Id<VineFabricPayload> id = type(message.wireId());
                    FabricClientNetwork.registerReceiver(id, (payload, playerUuid, playerName, mainThread) ->
                        driver.deliver(message.wireId(), Endpoint.CLIENT, playerUuid, playerName,
                            payload.data(), mainThread));
                }
            }
        }
    }

    /** Binds one push natively; skips (wire id, direction) pairs already bound. */
    private void bindNative(List<MessageSpec> messages) {
        int newlyBound = 0;
        for (MessageSpec message : messages) {
            if (!bound.add(message.wireId() + "|" + message.handlerEndpoint())) {
                continue;
            }
            CustomPayload.Id<VineFabricPayload> id = type(message.wireId());
            PacketCodec<PacketByteBuf, VineFabricPayload> codec = PacketCodec.of(
                (payload, buf) -> buf.writeByteArray(payload.data()),
                buf -> new VineFabricPayload(id, buf.readByteArray()));
            if (message.handlerEndpoint() == Endpoint.SERVER) {
                PayloadTypeRegistry.playC2S().register(id, codec);
                ServerPlayNetworking.registerGlobalReceiver(id, (payload, context) ->
                    deliver(message.wireId(), Endpoint.SERVER,
                        context.player().getUuid(), context.player().getName().getString(),
                        payload.data(), context.server()));
            } else {
                PayloadTypeRegistry.playS2C().register(id, codec);
                // The S2C receiver registers on the client dist (bindClientReceivers).
            }
            newlyBound++;
        }
        if (newlyBound > 0) {
            LOG.info("[VINE] net: bound {} new payload registration(s) ({} total across {} channel(s))",
                newlyBound, bound.size(), table.entries().size());
        }
    }


    private void deliver(VineId wireId, Endpoint receiving, java.util.UUID playerUuid, String playerName,
                         byte[] data, Executor mainThread) {
        DriverVinePlayer from = new DriverVinePlayer(playerUuid, playerName);
        engineSink.accept(wireId, receiving, from, data, mainThread);
        Consumer<HookEvent> hook = packetHook.get();
        if (hook != null) {
            hook.accept(new HookEvent.PacketReceive(wireId.toString(), from.uniqueId().toString(), data));
        }
    }
}
