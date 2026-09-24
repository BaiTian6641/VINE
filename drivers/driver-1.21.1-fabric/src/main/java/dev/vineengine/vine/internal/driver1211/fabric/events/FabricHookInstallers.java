package dev.vineengine.vine.internal.driver1211.fabric.events;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.registry.Registries;

import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookEvents;
import dev.vineengine.vine.hook.HookSlot;
import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;
import dev.vineengine.vine.internal.driver1211.common.events.HookSlots;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * Fabric 1.21.1 native sources bound to engine hook slots (sub-01 Stage C).
 * Binding a slot means: first subscription installs the loader listener, which
 * posts the normalized event to the engine bus; last close uninstalls.
 *
 * <p>Seams (Mixin-backed, sub-18 Stage E §5.9): {@code blockPlace} and
 * {@code worldSave} have no Fabric callback. The wave's single per-driver Mixin
 * config ({@code vine-1211-fabric.mixins.json}) injects into vanilla's own
 * {@code BlockItem#place} and {@code ServerWorld#save} and posts through
 * {@link MixinHookTap}, so install binds that tap's sink and uninstall clears
 * it. The injection site itself cannot be switched off (it is applied at class
 * load), so laziness lives in the tap: with zero consumers the injected body is
 * one null check, no event is allocated and vanilla is untouched.
 * Loader difference absorbed: Fabric API events have no {@code unregister}, so
 * uninstall flips the listener into a permanent no-op; zero consumers still mean
 * zero behavior change (one empty callback dispatch remains).
 *
 * <p>Content-driven slots ({@code registryRegister}, {@code packetReceive},
 * {@code commandExecute}) install by capturing the sink the live path posts to —
 * their native plumbing exists for the engine regardless.
 */
public final class FabricHookInstallers {

    /** Registers a native source on first subscribe; returns its uninstall action. */
    @FunctionalInterface
    private interface Binder {
        Runnable bind();
    }

    private FabricHookInstallers() {
    }

    /** Binds every Fabric-backed slot on {@code ctx}. */
    public static void bind(VineDriver.DriverContext ctx, EventBus bus) {
        bind(ctx, HookSlots.REGISTRY_REGISTER, () -> {
            AutoCloseable handle = RegistryHookTap.subscribe(bus::post);
            return () -> {
                try {
                    handle.close();
                } catch (Exception e) {
                    // Idempotent removal; a failed close only leaks one sink.
                }
            };
        });

        bind(ctx, HookSlots.BLOCK_BREAK, () -> {
            AtomicBoolean live = new AtomicBoolean(true);
            PlayerBlockBreakEvents.After listener = (world, player, pos, state, blockEntity) -> {
                if (live.get()) {
                    bus.post(new HookEvents.BlockBreak(
                        world.getRegistryKey().getValue().toString(),
                        Registries.BLOCK.getId(state.getBlock()).toString(),
                        pos.getX(), pos.getY(), pos.getZ(),
                        player.getUuidAsString()));
                }
            };
            PlayerBlockBreakEvents.AFTER.register(listener);
            // Fabric has no unregister: dormancy is the gate.
            return () -> live.set(false);
        });

        bind(ctx, HookSlots.BLOCK_PLACE, () -> {
            MixinHookTap.blockPlace(bus::post);
            return () -> MixinHookTap.blockPlace(null);
        });

        bind(ctx, HookSlots.WORLD_SAVE, () -> {
            MixinHookTap.worldSave(bus::post);
            return () -> MixinHookTap.worldSave(null);
        });

        bind(ctx, HookSlots.WORLD_LOAD, () -> {
            AtomicBoolean live = new AtomicBoolean(true);
            ServerWorldEvents.Load listener = (server, world) -> {
                if (live.get()) {
                    bus.post(new HookEvents.WorldLoad(world.getRegistryKey().getValue().toString()));
                }
            };
            ServerWorldEvents.LOAD.register(listener);
            return () -> live.set(false);
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
