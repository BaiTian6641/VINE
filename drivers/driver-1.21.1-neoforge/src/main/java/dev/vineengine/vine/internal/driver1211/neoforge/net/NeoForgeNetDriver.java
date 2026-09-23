package dev.vineengine.vine.internal.driver1211.neoforge.net;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

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
 * The 1.21.1 NeoForge {@link NetDriver} (sub-05 Stage A): engine wire ids map 1:1
 * to NF payload types over one opaque-bytes payload shape; the engine's
 * {@code ChannelSpec.protocol} becomes the registrar version
 * ({@code event.registrar(version)}), so NF's built-in version check IS the
 * channel version rule.
 *
 * <p>Binding model: the engine pushes registrations from mod construction on; the
 * driver stores the latest table and binds native payload types when NF fires
 * {@link RegisterPayloadHandlersEvent} (per the {@link NetDriver#register}
 * "last call it can honor" contract). NF registers a payload <i>type</i> once, so
 * a message handled on both endpoints becomes one {@code playBidirectional}
 * registration (loader difference absorbed: the engine's per-direction
 * {@link MessageSpec}s would otherwise double-register the type). NF wraps
 * payload handlers onto the main thread itself, so inbound delivery already runs
 * off the network thread; the executor handed to the engine sink is still the
 * server's, keeping engine-side re-dispatch a same-thread no-op.
 *
 * <p>C2S sending is client-only: wired by the dist-segregated client entrypoint
 * ({@code VineNeoForgeClient}) via {@link #clientSender}; on a dedicated server
 * {@link #sendToServer} is a documented no-op.
 */
public final class NeoForgeNetDriver implements NetDriver {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeNetDriver.class);
    private static final Map<VineId, CustomPacketPayload.Type<VineNeoForgePayload>> TYPES = new ConcurrentHashMap<>();

    private final ChannelTable table = new ChannelTable();
    private final Set<VineId> bound = ConcurrentHashMap.newKeySet();
    private final AtomicReference<Consumer<HookEvent>> packetHook;
    private volatile NetTransport.InboundSink engineSink;
    private static volatile BiConsumer<VineId, byte[]> clientSender;

    public NeoForgeNetDriver(AtomicReference<Consumer<HookEvent>> packetHook) {
        this.packetHook = packetHook;
    }

    /** The payload type for {@code wireId}, created once (shared with the client sender). */
    public static CustomPacketPayload.Type<VineNeoForgePayload> type(VineId wireId) {
        return TYPES.computeIfAbsent(wireId,
            id -> new CustomPacketPayload.Type<>(ResourceLocation.parse(id.toString())));
    }

    /** Installed by {@code VineNeoForgeClient} (client dist only) for C2S sends. */
    public static void clientSender(BiConsumer<VineId, byte[]> sender) {
        clientSender = sender;
    }

    /** Binds this driver as the process transport; called once from bootstrap. */
    public void bindTransport() {
        engineSink = NetTransportAdapter.bind(this);
    }

    @Override
    public void register(ChannelSpec spec, List<MessageSpec> messages) {
        table.put(spec, messages);
    }

    @Override
    public void send(VinePlayer player, VineId wireId, byte[] payload) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayer(player.uniqueId());
        if (target == null) {
            return;
        }
        PacketDistributor.sendToPlayer(target, new VineNeoForgePayload(type(wireId), payload));
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
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.getPlayerList().getPlayer(player.uniqueId()) != null;
    }

    /**
     * Binds the stored channel table to native payload types. Wired to
     * {@link RegisterPayloadHandlersEvent} on the mod bus; idempotent per wire id
     * so a duplicate call can never double-register.
     */
    public void bindNative(RegisterPayloadHandlersEvent event) {
        Map<String, PayloadRegistrar> byVersion = new HashMap<>();
        for (ChannelTable.Entry entry : table.entries()) {
            PayloadRegistrar registrar = byVersion.computeIfAbsent(
                Integer.toString(entry.spec().protocol()), event::registrar);
            Map<VineId, Set<Endpoint>> byWireId = new LinkedHashMap<>();
            for (MessageSpec message : entry.messages()) {
                byWireId.computeIfAbsent(message.wireId(), id -> EnumSet.noneOf(Endpoint.class))
                    .add(message.handlerEndpoint());
            }
            for (Map.Entry<VineId, Set<Endpoint>> wire : byWireId.entrySet()) {
                VineId wireId = wire.getKey();
                if (!bound.add(wireId)) {
                    continue;
                }
                boolean toServer = wire.getValue().contains(Endpoint.SERVER);
                boolean toClient = wire.getValue().contains(Endpoint.CLIENT);
                CustomPacketPayload.Type<VineNeoForgePayload> type = type(wireId);
                StreamCodec<RegistryFriendlyByteBuf, VineNeoForgePayload> codec = StreamCodec.of(
                    (buf, payload) -> buf.writeByteArray(payload.data()),
                    buf -> new VineNeoForgePayload(type, buf.readByteArray()));
                if (toServer && toClient) {
                    registrar.playBidirectional(type, codec, (payload, context) ->
                        deliverFlow(wireId, payload, context));
                } else if (toServer) {
                    registrar.playToServer(type, codec, (payload, context) ->
                        deliver(wireId, Endpoint.SERVER, context.player(), payload.data(),
                            context.player().getServer()));
                } else {
                    registrar.playToClient(type, codec, (payload, context) ->
                        deliver(wireId, Endpoint.CLIENT, context.player(), payload.data(),
                            NeoForgeClientNetwork.mainThreadExecutor()));
                }
            }
        }
        LOG.info("[VINE] net: bound {} payload registration(s) across {} channel(s) (registrar version = channel protocol)",
            bound.size(), table.entries().size());
    }

    /** Bidirectional handler: the flow tells which endpoint is receiving. */
    private void deliverFlow(VineId wireId, VineNeoForgePayload payload, IPayloadContext context) {
        if (context.flow() == PacketFlow.SERVERBOUND) {
            deliver(wireId, Endpoint.SERVER, context.player(), payload.data(), context.player().getServer());
        } else {
            deliver(wireId, Endpoint.CLIENT, context.player(), payload.data(),
                NeoForgeClientNetwork.mainThreadExecutor());
        }
    }

    private void deliver(VineId wireId, Endpoint receiving, Player nativePlayer, byte[] data, Executor mainThread) {
        DriverVinePlayer from = new DriverVinePlayer(
            nativePlayer.getUUID(), nativePlayer.getGameProfile().getName());
        engineSink.accept(wireId, receiving, from, data, mainThread);
        Consumer<HookEvent> hook = packetHook.get();
        if (hook != null) {
            hook.accept(new HookEvent.PacketReceive(wireId.toString(), from.uniqueId().toString(), data));
        }
    }
}
