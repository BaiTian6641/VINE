package dev.vineengine.vine.internal.driver1211.neoforge.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforge.registries.RegistryBuilder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.spi.RegistryDriver;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.spi.VineDriver;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.registry.VineId;

/**
 * NeoForge 1.21.1 structural materialization (sub-02 Stage B). Loader difference
 * absorbed (vs. Fabric's flat mod init): NF splits static registration into
 * {@link NewRegistryEvent} — one vanilla registry created per structural
 * descriptor type — followed by one {@link RegisterEvent} firing <em>per created
 * registry</em>, which fills that registry's entries. Both moments complete
 * before vanilla freeze (marked by {@code FMLCommonSetupEvent}, the driver's
 * {@code REGISTRIES_FROZEN} anchor).
 *
 * <p>Values registered are the descriptor data objects themselves; per-type
 * native delegates (blocks, items, …) are the content subsystems' concern
 * (sub-07+), never this generic machinery.
 *
 * <p>Sub-07 extension: the engine content kinds {@code vine:block} and
 * {@code vine:item} are routed away from the vine-created registries into the
 * <em>vanilla</em> BLOCK/ITEM registries via {@link NeoForgeContentMaterializer}
 * at those registries' own {@code RegisterEvent} firings.
 */
public final class NeoForgeStructuralMaterializer implements RegistryDriver {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeStructuralMaterializer.class);
    /** Snapshot slice for {@code vine:block}, non-null iff entries exist (sub-07). */
    private StructuralRegistryView.StructuralType blockType;
    /** Snapshot slice for {@code vine:item}, non-null iff entries exist (sub-07). */
    private StructuralRegistryView.StructuralType itemType;
    /** Types whose vanilla registry was created but whose RegisterEvent has not fired yet. */
    private final Map<ResourceLocation, StructuralRegistryView.StructuralType> pending = new LinkedHashMap<>();
    /** The in-flight NewRegistryEvent, set by the mod-bus listener around {@link #materializeStructural}. */
    private NewRegistryEvent newRegistryEvent;
    private int materialized;

    /**
     * Wires the two mod-bus moments. The store view is read at {@link NewRegistryEvent}
     * time — mod construction (and with it consumer init) has completed by then, so
     * the snapshot carries every structural type for this session.
     */
    public NeoForgeStructuralMaterializer(IEventBus modBus, VineDriver.DriverContext ctx) {
        modBus.addListener(NewRegistryEvent.class, event -> {
            newRegistryEvent = event;
            try {
                materializeStructural(ctx.structuralRegistries());
            } finally {
                newRegistryEvent = null;
            }
        });
        modBus.addListener(RegisterEvent.class, this::onRegister);
    }


    /**
     * NF materialization, phase one: create one vanilla static registry per
     * structural descriptor type. Phase two ({@link #onRegister}) fills each when
     * its own {@code RegisterEvent} fires.
     */
    @Override
    public void materializeStructural(StructuralRegistryView structural) {
        for (StructuralRegistryView.StructuralType type : structural.types()) {
            // Content kinds (sub-07) never get a vine-created registry: they
            // materialize into the VANILLA registries at their own
            // RegisterEvent firings (see onRegister) — a placeable block or
            // inventory-real item only exists as a vanilla singleton.
            if (type.type() == VineContent.BLOCK_TYPE) {
                blockType = type;
                continue;
            }
            if (type.type() == VineContent.ITEM_TYPE) {
                itemType = type;
                continue;
            }
            ResourceKey<Registry<Object>> key = registryKey(type.type().registryId());
            newRegistryEvent.create(new RegistryBuilder<>(key));
            pending.put(key.location(), type);
        }
    }

    /**
     * Fills one of our registries when its RegisterEvent fires, and routes the
     * vanilla BLOCK/ITEM firings to content materialization (sub-07); logs the
     * custom-registry total when the last drains.
     */
    private void onRegister(RegisterEvent event) {
        if (blockType != null && event.getRegistryKey().equals(Registries.BLOCK)) {
            NeoForgeContentMaterializer.registerBlocks(event, blockType);
        }
        if (itemType != null && event.getRegistryKey().equals(Registries.ITEM)) {
            NeoForgeContentMaterializer.registerItems(event, itemType);
        }
        StructuralRegistryView.StructuralType type = pending.remove(event.getRegistryKey().location());
        if (type == null) {
            return; // a vanilla or foreign registry's firing — not ours
        }
        event.register(registryKey(type.type().registryId()), helper -> {
            for (Holder<?> holder : type.entries()) {
                helper.register(location(holder.id()), holder.value());
                RegistryHookTap.dispatch(type.type().registryId().toString(), holder.id().toString());
                materialized++;
            }
        });
        if (pending.isEmpty() && materialized > 0) {
            LOG.info("vine: materialized {} structural entries", materialized);
        }
    }

    private static ResourceKey<Registry<Object>> registryKey(VineId id) {
        return ResourceKey.createRegistryKey(location(id));
    }

    private static ResourceLocation location(VineId id) {
        return ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }
}
