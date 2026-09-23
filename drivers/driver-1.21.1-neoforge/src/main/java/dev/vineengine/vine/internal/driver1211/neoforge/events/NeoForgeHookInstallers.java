package dev.vineengine.vine.internal.driver1211.neoforge.events;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;
import dev.vineengine.vine.internal.driver1211.common.events.HookBus;
import dev.vineengine.vine.internal.driver1211.common.events.HookEvent;
import dev.vineengine.vine.internal.driver1211.common.events.VineHook;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;

/**
 * NeoForge 1.21.1 native sources for the M0 hook set (sub-18 §2 table). Every
 * installer registers on the NF game bus and returns an {@code unregister} handle
 * so the last close dorms the hook (Minimal Footprint). {@code REGISTRY_REGISTER}
 * is backed by sub-02 Stage B's structural materialization tap — the engine's own
 * registration is the source, so there is no native listener to install.
 * {@code PACKET_RECEIVE} and {@code COMMAND_EXECUTE} are content-driven: their
 * native plumbing (sub-05 payload handlers / sub-06 command attach) exists for
 * the engine regardless, so the installer just captures the sink the live path
 * posts to while subscribed.
 * <p>Level events fire on both dists; installers filter to {@link ServerLevel} to
 * keep payload parity with Fabric's server-side callbacks.
 */
public final class NeoForgeHookInstallers {

    private NeoForgeHookInstallers() {
    }

    public static Map<VineHook, HookBus.Installer> create(AtomicReference<Consumer<HookEvent>> packetHook) {
        Map<VineHook, HookBus.Installer> installers = new EnumMap<>(VineHook.class);

        installers.put(VineHook.BLOCK_PLACE, sink -> {
            Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    String playerUuid = event.getEntity() instanceof Player player
                        ? player.getStringUUID() : "";
                    sink.accept(new HookEvent.BlockPlace(
                        level.dimension().location().toString(),
                        BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString(),
                        event.getPos().getX(), event.getPos().getY(), event.getPos().getZ(),
                        playerUuid));
                }
            };
            NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        installers.put(VineHook.BLOCK_BREAK, sink -> {
            Consumer<BlockEvent.BreakEvent> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    sink.accept(new HookEvent.BlockBreak(
                        level.dimension().location().toString(),
                        BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString(),
                        event.getPos().getX(), event.getPos().getY(), event.getPos().getZ(),
                        event.getPlayer().getStringUUID()));
                }
            };
            NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        installers.put(VineHook.WORLD_LOAD, sink -> {
            Consumer<LevelEvent.Load> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    sink.accept(new HookEvent.WorldLoad(level.dimension().location().toString()));
                }
            };
            NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        installers.put(VineHook.WORLD_SAVE, sink -> {
            Consumer<LevelEvent.Save> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    sink.accept(new HookEvent.WorldSave(level.dimension().location().toString()));
                }
            };
            NeoForge.EVENT_BUS.addListener(LevelEvent.Save.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        installers.put(VineHook.REGISTRY_REGISTER, RegistryHookTap::subscribe);

        installers.put(VineHook.PACKET_RECEIVE, sink -> {
            packetHook.set(sink);
            return () -> packetHook.compareAndSet(sink, null);
        });

        installers.put(VineHook.COMMAND_EXECUTE, sink -> {
            EngineCommands.executeHook(sink);
            return () -> EngineCommands.executeHook(null);
        });

        return installers;
    }
}
