package dev.vineengine.vine.internal.driver1211.fabric.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.spi.DesignRegistryView;
import dev.vineengine.vine.internal.spi.RegistryDriver;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.spi.VineDriver;
import dev.vineengine.vine.registry.VineId;

/**
 * Fabric 1.21.1 design-descriptor materialization (sub-02 Stage C): every DESIGN
 * type becomes a vanilla dynamic datapack registry via Fabric API's
 * {@link DynamicRegistries#registerSynced} (client-synced) or
 * {@link DynamicRegistries#register} (server-only), so datapack override,
 * per-world rebinding and the vanilla sync path come free.
 *
 * <p>Registration happens at mod init (the driver's registration moment on this
 * loader — same point as structural materialization). Entries are reported after
 * each world load ({@link ServerLifecycleEvents#SERVER_STARTED}); the report
 * replaces the previous set, so nothing is cached across worlds.
 */
public final class FabricDesignMaterializer implements RegistryDriver {

    private static final Logger LOG = LoggerFactory.getLogger(FabricDesignMaterializer.class);

    private final VineDriver.DriverContext ctx;
    private DesignRegistryView design;

    public FabricDesignMaterializer(VineDriver.DriverContext ctx) {
        this.ctx = ctx;
        ServerLifecycleEvents.SERVER_STARTED.register(this::report);
    }

    /** Deltas for structural materialization route to the structural materializer. */
    @Override
    public void materializeStructural(StructuralRegistryView structural) {
        // Structural kinds are handled by FabricStructuralMaterializer; this
        // class owns the DESIGN half only.
    }

    @Override
    public void registerDesign(DesignRegistryView design) {
        this.design = design;
        for (DesignRegistryView.DesignType type : design.types()) {
            RegistryKey<Registry<Object>> key = RegistryKey.ofRegistry(
                Identifier.of(type.registryId().namespace(), type.registryId().path()));
            @SuppressWarnings("unchecked")
            Codec<Object> codec = (Codec<Object>) type.codec();
            if (type.syncToClient()) {
                DynamicRegistries.registerSynced(key, codec, codec);
            } else {
                DynamicRegistries.register(key, codec);
            }
            LOG.info("[VINE] design registry registered: {} (sync={}, skipWhenEmpty={})",
                type.registryId(), type.syncToClient(), type.skipWhenEmpty());
        }
    }

    private void report(MinecraftServer server) {
        DesignRegistryView view = design;
        if (view == null) {
            return;
        }
        for (DesignRegistryView.DesignType type : view.types()) {
            RegistryKey<Registry<Object>> key = RegistryKey.ofRegistry(
                Identifier.of(type.registryId().namespace(), type.registryId().path()));
            Map<VineId, Object> entries = new LinkedHashMap<>();
            server.getRegistryManager().getOptional(key).ifPresent(registry -> {
                for (Map.Entry<RegistryKey<Object>, Object> entry : registry.getEntrySet()) {
                    Identifier id = entry.getKey().getValue();
                    entries.put(VineId.of(id.getNamespace(), id.getPath()), entry.getValue());
                }
            });
            ctx.reportDesignEntries(type.registryId(), entries);
            LOG.info("[VINE] design registry {} reported {} entrie(s)", type.registryId(), entries.size());
        }
    }
}
