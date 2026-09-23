package dev.vineengine.vine.internal.driver1211.neoforge.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.RegisterEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;

/**
 * Native block/item materialization for the 1.21.1 NeoForge cell (sub-07
 * Stage A): the sub-02 seam extension for content kinds. Unlike generic
 * structural types (which get a vine-created registry), {@code vine:block} and
 * {@code vine:item} entries land in the <em>vanilla</em> BLOCK and ITEM
 * registries — a placeable/breakable block and an inventory-real item only
 * exist as vanilla singletons.
 *
 * <p>Loader difference absorbed (vs. Fabric's plain mod-init registration): NF
 * registers vanilla-registry entries inside that registry's own
 * {@link RegisterEvent} firing, so this class is driven by
 * {@link NeoForgeStructuralMaterializer} at the BLOCK and ITEM events, after
 * the store snapshot has been captured.
 *
 * <p>Stage A shape: one default-state block per BlockDescriptor (tuning mapped
 * 1:1 onto {@code BlockBehaviour.Properties}), one item per ItemDescriptor. No
 * behaviors, states, or block entities exist yet — none are installed
 * (Minimal Footprint).
 */
public final class NeoForgeContentMaterializer {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeContentMaterializer.class);

    private NeoForgeContentMaterializer() {
    }

    /** Registers every {@code vine:block} entry into the vanilla block registry. */
    public static void registerBlocks(RegisterEvent event, StructuralRegistryView.StructuralType type) {
        event.register(Registries.BLOCK, helper -> {
            for (Holder<?> holder : type.entries()) {
                BlockDescriptor descriptor = (BlockDescriptor) holder.value();
                requireMatchingIds(holder, descriptor.id());
                helper.register(location(descriptor.id()), new Block(BlockBehaviour.Properties.of()
                    .strength(descriptor.tuning().hardness(), descriptor.tuning().resistance())));
                RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
                LOG.info("vine: materialized block {} (model={})", descriptor.id(), descriptor.model().kind());
            }
        });
    }

    /** Registers every {@code vine:item} entry into the vanilla item registry. */
    public static void registerItems(RegisterEvent event, StructuralRegistryView.StructuralType type) {
        event.register(Registries.ITEM, helper -> {
            for (Holder<?> holder : type.entries()) {
                ItemDescriptor descriptor = (ItemDescriptor) holder.value();
                requireMatchingIds(holder, descriptor.id());
                helper.register(location(descriptor.id()), new Item(new Item.Properties()
                    .stacksTo(descriptor.tuning().stackSize())));
                RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
                LOG.info("vine: materialized item {} (model={})", descriptor.id(), descriptor.model().kind());
            }
        });
    }

    /** The holder key owns the entry; a disagreeing descriptor id is an author bug, never guessed. */
    private static void requireMatchingIds(Holder<?> holder, VineId descriptorId) {
        if (!holder.id().equals(descriptorId)) {
            throw new IllegalStateException("content descriptor id " + descriptorId
                + " disagrees with its registration id " + holder.id() + " — the engine never guesses");
        }
    }

    private static ResourceLocation location(VineId id) {
        return ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }
}
