package dev.vineengine.vine.internal.driver1211.neoforge.events;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import dev.vineengine.vine.internal.driver1211.common.events.CommandQueue;
import dev.vineengine.vine.internal.driver1211.common.events.HookBus;
import dev.vineengine.vine.internal.driver1211.common.events.HookEvent;
import dev.vineengine.vine.internal.driver1211.common.events.VineHook;

/**
 * NeoForge 1.21.1 native sources for the M0 hook set (sub-18 §2 table). Every
 * installer registers on the NF game bus and returns an {@code unregister} handle
 * so the last close dorms the hook (Minimal Footprint). Seams (no installer):
 * {@code REGISTRY_REGISTER} waits on sub-02's driver registry SPI,
 * {@code PACKET_RECEIVE} on sub-05's payload SPI (both sub-18 Stage D).
 *
 * <p>Level events fire on both dists; installers filter to {@link ServerLevel} to
 * keep payload parity with Fabric's server-side callbacks.
 */
public final class NeoForgeHookInstallers {

    private NeoForgeHookInstallers() {
    }

    public static Map<VineHook, HookBus.Installer> create(CommandQueue commands) {
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

        installers.put(VineHook.COMMAND_EXECUTE, sink -> {
            Consumer<RegisterCommandsEvent> listener = event -> {
                for (String literal : commands.drain()) {
                    event.getDispatcher().register(Commands.literal(literal).executes(context -> {
                        sink.accept(new HookEvent.CommandExecute(
                            context.getInput(), context.getSource().getTextName()));
                        return 1;
                    }));
                }
            };
            NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        return installers;
    }
}
