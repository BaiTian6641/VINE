package dev.vineengine.vine.internal.driver1211.fabric.registry;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.state.StateManager;
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
 * <p>Shape: one block per BlockDescriptor (tuning mapped 1:1 onto
 * {@code AbstractBlock.Settings}) whose state manager is the descriptor's
 * flattened state model (sub-07 Stage B, {@link FabricBlockStates}), one item per
 * ItemDescriptor. Behaviors do not exist yet — none are installed (Minimal
 * Footprint); a flagged descriptor's block materializes with a block-entity type
 * carrying the engine storage (sub-07 Stage C).
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
            AbstractBlock.Settings settings = AbstractBlock.Settings.create()
                .strength(descriptor.tuning().hardness(), descriptor.tuning().resistance());
            Block block;
            if (descriptor.blockEntity()) {
                // A flagged block gets a block-entity type whose instances are the
                // engine carrier (sub-07 Stage C, minimal): the payload rides the
                // cell's attachment, so persistence applies with no behavior code.
                // The block holds its type in a setter because the type's factory
                // needs the block — the one circularity the vanilla API forces.
                EngineBlockEntityBlock engineBlock = EngineBlockEntityBlock.materialize(descriptor, settings);
                block = engineBlock;
                net.minecraft.block.entity.BlockEntityType<EngineBlockEntity> beType =
                    net.minecraft.block.entity.BlockEntityType.Builder
                        .create((pos, state) -> new EngineBlockEntity(engineBlock.vineType, pos, state), block)
                        .build();
                Registry.register(Registries.BLOCK_ENTITY_TYPE, identifier(descriptor.id()), beType);
                engineBlock.vineType = beType;
                LOG.info("vine: materialized block entity type for {} (Fabric)", descriptor.id());
            } else {
                block = EngineBlock.materialize(descriptor, settings);
            }
            // The native default state must be the engine's default state
            // (sub-07 Stage B): checked before the block reaches the registry, so a
            // carrier that disagrees with the descriptor fails the boot instead of
            // becoming a "freshly placed" state nobody can compare.
            FabricBlockStates.requireDefaultState(descriptor, block);
            Registry.register(Registries.BLOCK, identifier(descriptor.id()), block);
            BLOCKS.put(descriptor.id(), block);
            RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
            LOG.info("vine: materialized block {} (model={})", descriptor.id(), descriptor.model().kind());
        }
    }

    /**
     * The engine's block: a vanilla block carrying one descriptor's flattened
     * state model (sub-07 Stage B), constructed through
     * {@link FabricBlockStates#withStateModel} because the state manager is built
     * inside the {@code Block} constructor — before this class's fields can hold
     * the descriptor's properties.
     */
    public static class EngineBlock extends Block {

        private final VineId id;

        EngineBlock(AbstractBlock.Settings settings, VineId id) {
            super(settings);
            this.id = id;
        }

        /** Constructs the block for {@code descriptor}, its state model installed. */
        static EngineBlock materialize(BlockDescriptor descriptor, AbstractBlock.Settings settings) {
            return FabricBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlock(settings, descriptor.id()));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            FabricBlockStates.installStateModel(builder);
        }

        /** The engine block descriptor this native block was materialized for. */
        public VineId vineId() {
            return id;
        }
    }

    /**
     * The engine's block with the block-entity facet: Fabric's
     * {@code BlockEntityProvider} is the interface that owns block-entity creation
     * (sub-07 Stage C), and the state model is the same one {@link EngineBlock}
     * carries.
     */
    public static final class EngineBlockEntityBlock extends EngineBlock
            implements net.minecraft.block.BlockEntityProvider {

        volatile net.minecraft.block.entity.BlockEntityType<EngineBlockEntity> vineType;

        EngineBlockEntityBlock(AbstractBlock.Settings settings, VineId id) {
            super(settings, id);
        }

        /** Constructs the block for a flagged {@code descriptor}, its state model installed. */
        static EngineBlockEntityBlock materialize(BlockDescriptor descriptor, AbstractBlock.Settings settings) {
            return FabricBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlockEntityBlock(settings, descriptor.id()));
        }

        @Override
        public net.minecraft.block.entity.BlockEntity createBlockEntity(net.minecraft.util.math.BlockPos pos,
                net.minecraft.block.BlockState state) {
            net.minecraft.block.entity.BlockEntityType<EngineBlockEntity> type = vineType;
            return type == null ? null : new EngineBlockEntity(type, pos, state);
        }
    }

    /** The engine's block entity: no behavior, just the attachment-carried payload. */
    public static final class EngineBlockEntity extends net.minecraft.block.entity.BlockEntity {

        public EngineBlockEntity(net.minecraft.block.entity.BlockEntityType<?> type,
                net.minecraft.util.math.BlockPos pos, net.minecraft.block.BlockState state) {
            super(type, pos, state);
        }
    }

    /** The materialized block for {@code id}, or null when none was. */
    public static Block blockFor(VineId id) {
        return BLOCKS.get(id);
    }

    private static final java.util.Map<VineId, Block> BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Materialized items by descriptor id — the voxel probe's attach targets. */
    private static final java.util.Map<VineId, Item> ITEMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The item materialized for {@code id}, or null when none was. */
    public static Item itemFor(VineId id) {
        return ITEMS.get(id);
    }

    /** Registers every {@code vine:item} entry into the vanilla item registry. */
    public static void registerItems(StructuralRegistryView.StructuralType type) {
        for (Holder<?> holder : type.entries()) {
            ItemDescriptor descriptor = (ItemDescriptor) holder.value();
            requireMatchingIds(holder, descriptor.id());
            Item item = new Item(new Item.Settings().maxCount(descriptor.tuning().stackSize()));
            Registry.register(Registries.ITEM, identifier(descriptor.id()), item);
            ITEMS.put(holder.id(), item);
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
