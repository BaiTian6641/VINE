package dev.vineengine.vine.internal.driver1211.neoforge.events;


import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

import java.util.function.Consumer;

import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookEvents;
import dev.vineengine.vine.hook.HookSlot;
import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;
import dev.vineengine.vine.internal.driver1211.common.events.HookSlots;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * NeoForge 1.21.1 native sources bound to engine hook slots (sub-01 Stage C).
 * Binding a slot means: first subscription registers the NF game-bus listener,
 * which posts the normalized event to the engine bus; last close unregisters
 * (Minimal Footprint §5.1 — NF's bus has real unregistration).
 *
 * <p>Content-driven slots ({@code registryRegister}, {@code packetReceive},
 * {@code commandExecute}) install by capturing the sink the live path posts to —
 * their native plumbing exists for the engine regardless.
 * <p>Level events fire on both dists; installers filter to {@link ServerLevel} to
 * keep payload parity with Fabric's server-side callbacks.
 */
public final class NeoForgeHookInstallers {

    /** Registers a native source on first subscribe; returns its uninstall action. */
    @FunctionalInterface
    private interface Binder {
        Runnable bind();
    }

    private NeoForgeHookInstallers() {
    }

    /** Binds every NF-backed slot on {@code ctx}. */
    public static void bind(VineDriver.DriverContext ctx, EventBus bus) {
        bind(ctx, HookSlots.BLOCK_PLACE, () -> {
            Consumer<BlockEvent.EntityPlaceEvent> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    String playerUuid = event.getEntity() instanceof Player player
                        ? player.getStringUUID() : "";
                    bus.post(new HookEvents.BlockPlace(
                        level.dimension().location().toString(),
                        BuiltInRegistries.BLOCK.getKey(event.getPlacedBlock().getBlock()).toString(),
                        event.getPos().getX(), event.getPos().getY(), event.getPos().getZ(),
                        playerUuid));
                }
            };
            NeoForge.EVENT_BUS.addListener(BlockEvent.EntityPlaceEvent.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        bind(ctx, HookSlots.BLOCK_BREAK, () -> {
            Consumer<BlockEvent.BreakEvent> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    bus.post(new HookEvents.BlockBreak(
                        level.dimension().location().toString(),
                        BuiltInRegistries.BLOCK.getKey(event.getState().getBlock()).toString(),
                        event.getPos().getX(), event.getPos().getY(), event.getPos().getZ(),
                        event.getPlayer().getStringUUID()));
                }
            };
            NeoForge.EVENT_BUS.addListener(BlockEvent.BreakEvent.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        bind(ctx, HookSlots.WORLD_LOAD, () -> {
            Consumer<LevelEvent.Load> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    bus.post(new HookEvents.WorldLoad(level.dimension().location().toString()));
                }
            };
            NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        bind(ctx, HookSlots.WORLD_SAVE, () -> {
            Consumer<LevelEvent.Save> listener = event -> {
                if (event.getLevel() instanceof ServerLevel level) {
                    bus.post(new HookEvents.WorldSave(level.dimension().location().toString()));
                }
            };
            NeoForge.EVENT_BUS.addListener(LevelEvent.Save.class, listener);
            return () -> NeoForge.EVENT_BUS.unregister(listener);
        });

        bind(ctx, HookSlots.REGISTRY_REGISTER, () -> {
            AutoCloseable handle = RegistryHookTap.subscribe(bus::post);
            return () -> {
                try {
                    handle.close();
                } catch (Exception e) {
                    // AutoCloseable.close throwing here is a RegistryHookTap bug; the
                    // removal is idempotent, so a failed uninstall only leaks one sink.
                }
            };
        });

        bind(ctx, HookSlots.COMMAND_EXECUTE, () -> {
            EngineCommands.executeHook(bus::post);
            return () -> EngineCommands.executeHook(null);
        });
    }

    /** Wires install/uninstall around a {@link Binder}: install returns the uninstall action. */
    private static void bind(VineDriver.DriverContext ctx, HookSlot slot, Binder binder) {
        Runnable[] uninstall = {() -> { }};
        ctx.installHook(slot,
            () -> uninstall[0] = binder.bind(),
            () -> uninstall[0].run());
    }
}
