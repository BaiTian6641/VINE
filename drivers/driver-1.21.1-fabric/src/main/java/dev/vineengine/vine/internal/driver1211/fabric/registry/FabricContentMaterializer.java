package dev.vineengine.vine.internal.driver1211.fabric.registry;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;

/**
 * Native block/item materialization for the 1.21.1 Fabric cell (sub-07
 * Stage A): the sub-02 seam extension for content kinds. Unlike generic
 * structural types (which get a vine-created registry), {@code vine:block} and
 * {@code vine:item} entries land in the <em>vanilla</em> BLOCK and ITEM
 * registries — a placeable/breakable block and an inventory-real item only
 * exist as vanilla singletons.
 *
 * <p>Loader difference absorbed (vs. NeoForge's per-registry
 * {@code RegisterEvent} phasing): Fabric's registration moment is plain
 * {@code Registry.register} during mod init, immediately after consumer
 * initializers have populated the descriptor store.
 *
 * <p>Stage A shape: one default-state block per BlockDescriptor (tuning mapped
 * 1:1 onto {@code AbstractBlock.Settings}), one item per ItemDescriptor. No
 * behaviors, states, or block entities exist yet — none are installed
 * (Minimal Footprint).
 */
public final class FabricContentMaterializer {

    private static final Logger LOG = LoggerFactory.getLogger(FabricContentMaterializer.class);

    private FabricContentMaterializer() {
    }

    /** Registers every {@code vine:block} entry into the vanilla block registry. */
    public static void registerBlocks(StructuralRegistryView.StructuralType type) {
        for (Holder<?> holder : type.entries()) {
            BlockDescriptor descriptor = (BlockDescriptor) holder.value();
            requireMatchingIds(holder, descriptor.id());
            Registry.register(Registries.BLOCK, identifier(descriptor.id()), new Block(AbstractBlock.Settings.create()
                .strength(descriptor.tuning().hardness(), descriptor.tuning().resistance())));
            RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
            LOG.info("vine: materialized block {} (model={})", descriptor.id(), descriptor.model().kind());
        }
    }

    /** Registers every {@code vine:item} entry into the vanilla item registry. */
    public static void registerItems(StructuralRegistryView.StructuralType type) {
        for (Holder<?> holder : type.entries()) {
            ItemDescriptor descriptor = (ItemDescriptor) holder.value();
            requireMatchingIds(holder, descriptor.id());
            Registry.register(Registries.ITEM, identifier(descriptor.id()), new Item(new Item.Settings()
                .maxCount(descriptor.tuning().stackSize())));
            RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
            LOG.info("vine: materialized item {} (model={})", descriptor.id(), descriptor.model().kind());
        }
    }

    /** The holder key owns the entry; a disagreeing descriptor id is an author bug, never guessed. */
    private static void requireMatchingIds(Holder<?> holder, VineId descriptorId) {
        if (!holder.id().equals(descriptorId)) {
            throw new IllegalStateException("content descriptor id " + descriptorId
                + " disagrees with its registration id " + holder.id() + " — the engine never guesses");
        }
    }

    private static Identifier identifier(VineId id) {
        return Identifier.of(id.namespace(), id.path());
    }
}
