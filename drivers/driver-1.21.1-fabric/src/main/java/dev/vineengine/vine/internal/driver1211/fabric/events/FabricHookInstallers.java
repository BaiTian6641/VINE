package dev.vineengine.vine.internal.driver1211.fabric.events;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;

import dev.vineengine.vine.internal.driver1211.common.events.CommandQueue;
import dev.vineengine.vine.internal.driver1211.common.events.HookBus;
import dev.vineengine.vine.internal.driver1211.common.events.HookEvent;
import dev.vineengine.vine.internal.driver1211.common.events.VineHook;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;

/**
 * Fabric 1.21.1 native sources for the M0 hook set (sub-18 §2 table). Seams (no
 * installer): {@code BLOCK_PLACE} and {@code WORLD_SAVE} have no Fabric callback —
 * they wait on the quarantined per-driver Mixin config († rows, deferred with the
 * Mixin work); {@code PACKET_RECEIVE} waits on sub-05's payload SPI (sub-18
 * Stage D). {@code REGISTRY_REGISTER} is backed by sub-02 Stage B's structural
 * materialization tap — the engine's own registration is the source. Subscribing
 * to a seam hook throws, never misfires.
 *
 * <p>Loader difference absorbed: Fabric API events have no {@code unregister}
 * (unlike the NF bus), so dormancy is a gate — the returned close handle flips the
 * listener into a permanent no-op instead of detaching it. Zero consumers still
 * means zero behavior change; the residual cost is one empty callback dispatch,
 * which sub-01's {@code HookSlot} machinery may later tighten.
 */
public final class FabricHookInstallers {

    private FabricHookInstallers() {
    }

    public static Map<VineHook, HookBus.Installer> create(CommandQueue commands) {
        Map<VineHook, HookBus.Installer> installers = new EnumMap<>(VineHook.class);
        installers.put(VineHook.REGISTRY_REGISTER, RegistryHookTap::subscribe);

        installers.put(VineHook.BLOCK_BREAK, sink -> {
            AtomicBoolean live = new AtomicBoolean(true);
            PlayerBlockBreakEvents.After listener = (world, player, pos, state, blockEntity) -> {
                if (live.get()) {
                    sink.accept(new HookEvent.BlockBreak(
                        world.getRegistryKey().getValue().toString(),
                        Registries.BLOCK.getId(state.getBlock()).toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        player.getUuidAsString()));
                }
            };
            PlayerBlockBreakEvents.AFTER.register(listener);
            return () -> live.set(false);
        });

        installers.put(VineHook.WORLD_LOAD, sink -> {
            AtomicBoolean live = new AtomicBoolean(true);
            ServerWorldEvents.Load listener = (server, world) -> {
                if (live.get()) {
                    sink.accept(new HookEvent.WorldLoad(world.getRegistryKey().getValue().toString()));
                }
            };
            ServerWorldEvents.LOAD.register(listener);
            return () -> live.set(false);
        });

        installers.put(VineHook.COMMAND_EXECUTE, sink -> {
            AtomicBoolean live = new AtomicBoolean(true);
            CommandRegistrationCallback listener = (dispatcher, registryAccess, environment) -> {
                if (live.get()) {
                    for (String literal : commands.drain()) {
                        dispatcher.register(CommandManager.literal(literal).executes(context -> {
                            sink.accept(new HookEvent.CommandExecute(
                                context.getInput(), context.getSource().getName()));
                            return 1;
                        }));
                    }
                }
            };
            CommandRegistrationCallback.EVENT.register(listener);
            return () -> live.set(false);
        });

        return installers;
    }
}
