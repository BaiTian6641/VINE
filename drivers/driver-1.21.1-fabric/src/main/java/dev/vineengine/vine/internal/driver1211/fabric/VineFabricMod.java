package dev.vineengine.vine.internal.driver1211.fabric;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.common.DriverBoot;
import dev.vineengine.vine.internal.driver1211.fabric.boot.Fabric1211Driver;

/**
 * Common entrypoint for the 1.21.1 Fabric cell. Triggers the engine boot
 * ({@link DriverBoot#boot} → {@code VineEngine.get()} → ServiceLoader binds
 * {@link Fabric1211Driver} → {@code bootstrap}); the client init lives in
 * {@link VineFabricClient} behind the {@code "client"} entrypoint (§2), which a
 * dedicated server never loads.
 */
public final class VineFabricMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(VineFabricMod.class);

    @Override
    public void onInitialize() {
        LOGGER.info("VINE driver 1.21.1-fabric alive");
        DriverBoot.boot();
        // Structural materialization (sub-02 Stage B): Fabric's moment is mod
        // init, after boot has run consumer initializers (NF defers to its
        // RegisterEvent instead — loader difference absorbed in the driver).
        Fabric1211Driver.materializeStructuralRegistries();
    }
}
