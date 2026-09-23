package dev.vineengine.vine.internal.driver1211.neoforge.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mojang.serialization.Codec;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.spi.DesignRegistryView;
import dev.vineengine.vine.internal.spi.RegistryDriver;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.spi.VineDriver;
import dev.vineengine.vine.registry.VineId;

/**
 * NeoForge 1.21.1 design-descriptor materialization (sub-02 Stage C): every
 * DESIGN type becomes a vanilla <em>dynamic datapack registry</em> via
 * {@link DataPackRegistryEvent.NewRegistry}, so datapack override, per-world
 * rebinding and (when {@code syncToClient}) the vanilla sync path come free.
 *
 * <p>Entries are reported to the engine after each world load
 * ({@link ServerStartedEvent}) — the registry access is per-world, and the report
 * <em>replaces</em> the previous set, so nothing is cached across worlds. The
 * engine never parses JSON: the datapack loader decodes through the descriptor
 * {@code Codec}.
 */
public final class NeoForgeDesignMaterializer implements RegistryDriver {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeDesignMaterializer.class);

    private final VineDriver.DriverContext ctx;

    public NeoForgeDesignMaterializer(IEventBus modBus, VineDriver.DriverContext ctx) {
        this.ctx = ctx;
        modBus.addListener(DataPackRegistryEvent.NewRegistry.class, event -> {
            // Fresh snapshot: this event fires after mod construction (consumer
            // initializers have run), while the driver's bootstrap runs inside the
            // mod constructor — a view captured there would see no types at all.
            DesignRegistryView view = ctx.designRegistries();
            if (view.types().isEmpty()) {
                return; // no design types defined — Minimal Footprint, no event work
            }
            for (DesignRegistryView.DesignType type : view.types()) {
                ResourceKey<Registry<Object>> key = key(type.registryId());
                @SuppressWarnings("unchecked")
                Codec<Object> codec = (Codec<Object>) type.codec();
                if (type.syncToClient()) {
                    // M1 sync codec = the descriptor codec (its JSON form); a
                    // dedicated network codec is a later refinement.
                    event.dataPackRegistry(key, codec, codec);
                } else {
                    event.dataPackRegistry(key, codec);
                }
                LOG.info("[VINE] design registry registered: {} (sync={}, skipWhenEmpty={})",
                    type.registryId(), type.syncToClient(), type.skipWhenEmpty());
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
            event -> report(event.getServer()));
    }

    /** Deltas for structural materialization route to the structural materializer. */
    @Override
    public void materializeStructural(StructuralRegistryView structural) {
        // Structural kinds are handled by NeoForgeStructuralMaterializer; this
        // class owns the DESIGN half only.
    }

    @Override
    public void registerDesign(DesignRegistryView design) {
        // NF registers lazily inside DataPackRegistryEvent.NewRegistry (the view is
        // read fresh there); this hook exists for loaders whose registration is an
        // explicit mod-init call.
    }

    private void report(MinecraftServer server) {
        DesignRegistryView view = ctx.designRegistries();
        if (view.types().isEmpty()) {
            return;
        }
        for (DesignRegistryView.DesignType type : view.types()) {
            ResourceKey<Registry<Object>> key = key(type.registryId());
            Registry<Object> registry = server.registryAccess().registryOrThrow(key);
            Map<VineId, Object> entries = new LinkedHashMap<>();
            for (Map.Entry<ResourceKey<Object>, Object> entry : registry.entrySet()) {
                ResourceLocation id = entry.getKey().location();
                entries.put(VineId.of(id.getNamespace(), id.getPath()), entry.getValue());
            }
            ctx.reportDesignEntries(type.registryId(), entries);
            LOG.info("[VINE] design registry {} reported {} entrie(s)", type.registryId(), entries.size());
        }
    }

    private static ResourceKey<Registry<Object>> key(VineId registryId) {
        return ResourceKey.createRegistryKey(
            ResourceLocation.fromNamespaceAndPath(registryId.namespace(), registryId.path()));
    }
}
