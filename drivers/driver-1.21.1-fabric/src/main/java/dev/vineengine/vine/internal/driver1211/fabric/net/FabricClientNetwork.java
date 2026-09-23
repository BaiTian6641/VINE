package dev.vineengine.vine.internal.driver1211.fabric.net;

import java.util.UUID;
import java.util.concurrent.Executor;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.CustomPayload;

/**
 * Client-dist-only network helpers (sub-05 Stage A). Referenced exclusively from
 * paths that run on a physical client — {@code VineFabricClient}'s receiver/sender
 * wiring — so the dedicated server never loads this class (§2 dist segregation;
 * Fabric does not load {@code "client"} entrypoints there, and every reference
 * site sits behind that entrypoint).
 */
public final class FabricClientNetwork {

    /** Inbound adapter: the four pieces a client S2C delivery needs, client-type-free. */
    @FunctionalInterface
    public interface ClientInbound {
        void receive(VineFabricPayload payload, UUID playerUuid, String playerName, Executor mainThread);
    }

    private FabricClientNetwork() {
    }

    /** Sends one payload C2S over the current play connection (no-op when disconnected). */
    public static void sendToServer(VineFabricPayload payload) {
        if (MinecraftClient.getInstance().getNetworkHandler() != null) {
            ClientPlayNetworking.send(payload);
        }
    }

    /** Registers the S2C receiver for one payload id. */
    public static void registerReceiver(CustomPayload.Id<VineFabricPayload> id, ClientInbound inbound) {
        ClientPlayNetworking.registerGlobalReceiver(id, (payload, context) -> {
            UUID uuid = context.player() != null ? context.player().getUuid() : new UUID(0, 0);
            String name = context.player() != null ? context.player().getName().getString() : "";
            inbound.receive(payload, uuid, name, context.client());
        });
    }
}
