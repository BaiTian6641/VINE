package dev.vineengine.vine.internal.driver1211.neoforge;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.common.DriverBoot;
import dev.vineengine.vine.internal.driver1211.neoforge.boot.NeoForge1211Driver;

/**
 * Common entrypoint for the 1.21.1 NeoForge cell. Triggers the engine boot
 * ({@link DriverBoot#boot} → {@code VineEngine.get()} → ServiceLoader binds
 * {@link NeoForge1211Driver} → {@code bootstrap}); the client init lives in the
 * dist-segregated {@link VineNeoForgeClient} sibling (§2), which a dedicated
 * server never loads.
 *
 * <p>The injected mod event bus is handed to the driver before the engine boot:
 * NF registry/lifecycle events live on it, and {@code bootstrap} (running inside
 * {@code VineEngine.get()}) needs it to anchor {@code REGISTRIES_FROZEN}.
 */
@Mod("vine")
public final class VineMod {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineMod.class);

    public VineMod(IEventBus modEventBus) {
        LOGGER.info("VINE driver 1.21.1-neoforge alive");
        NeoForge1211Driver.handOffModEventBus(modEventBus);
        DriverBoot.boot(LOGGER);
    }
}
