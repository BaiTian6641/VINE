package dev.vineengine.vine.internal.driver1211.neoforge.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
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
 * <p>Shape: one block per BlockDescriptor (tuning mapped 1:1 onto
 * {@code BlockBehaviour.Properties}) whose state definition is the descriptor's
 * flattened state model (sub-07 Stage B, {@link NeoForgeBlockStates}), one item
 * per ItemDescriptor. Behaviors do not exist yet — none are installed (Minimal
 * Footprint); a flagged descriptor's block materializes with a block-entity type
 * carrying the engine storage (sub-07 Stage C).
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
                BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                    .strength(descriptor.tuning().hardness(), descriptor.tuning().resistance());
                Block block;
                if (descriptor.blockEntity()) {
                    // A flagged block gets a block-entity type whose instances are
                    // the engine carrier (sub-07 Stage C, minimal): the payload rides
                    // NeoForge's attachment, so persistence applies with no behavior
                    // code. The type itself is registered in ITS OWN registry's
                    // pass ({@link #registerBlockEntities}) — NeoForge forbids
                    // nesting one registry's registration inside another's, and the
                    // block must exist first anyway.
                    EngineBlockEntityBlock engineBlock = EngineBlockEntityBlock.materialize(descriptor, properties);
                    block = engineBlock;
                    PENDING_BLOCK_ENTITIES.put(descriptor.id(), engineBlock);
                } else {
                    block = EngineBlock.materialize(descriptor, properties);
                }
                // The native default state must be the engine's default state
                // (sub-07 Stage B): checked before the block reaches the registry,
                // so a carrier that disagrees with the descriptor fails the boot
                // instead of becoming a "freshly placed" state nobody can compare.
                NeoForgeBlockStates.requireDefaultState(descriptor, block);
                helper.register(location(descriptor.id()), block);
                BLOCKS.put(descriptor.id(), block);
                RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
                LOG.info("vine: materialized block {} (model={})", descriptor.id(), descriptor.model().kind());
            }
        });
    }

    /**
     * The engine's block: a vanilla block carrying one descriptor's flattened
     * state model (sub-07 Stage B), constructed through
     * {@link NeoForgeBlockStates#withStateModel} because the state definition is
     * built inside the {@code Block} constructor — before this class's fields can
     * hold the descriptor's properties.
     */
    public static class EngineBlock extends Block {

        private final VineId id;

        EngineBlock(BlockBehaviour.Properties properties, VineId id) {
            super(properties);
            this.id = id;
        }

        /** Constructs the block for {@code descriptor}, its state model installed. */
        static EngineBlock materialize(BlockDescriptor descriptor, BlockBehaviour.Properties properties) {
            return NeoForgeBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlock(properties, descriptor.id()));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            NeoForgeBlockStates.installStateModel(builder);
        }

        /** The engine block descriptor this native block was materialized for. */
        public VineId vineId() {
            return id;
        }
    }

    /**
     * The engine's block with the block-entity facet: {@code EntityBlock.newBlockEntity}
     * is the Mojmap hook that owns block-entity creation, and the state model is
     * the same one {@link EngineBlock} carries.
     */
    public static final class EngineBlockEntityBlock extends EngineBlock
            implements net.minecraft.world.level.block.EntityBlock {

        volatile net.minecraft.world.level.block.entity.BlockEntityType<EngineBlockEntity> vineType;

        EngineBlockEntityBlock(BlockBehaviour.Properties properties, VineId id) {
            super(properties, id);
        }

        /** Constructs the block for a flagged {@code descriptor}, its state model installed. */
        static EngineBlockEntityBlock materialize(BlockDescriptor descriptor, BlockBehaviour.Properties properties) {
            return NeoForgeBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlockEntityBlock(properties, descriptor.id()));
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos,
                BlockState state) {
            net.minecraft.world.level.block.entity.BlockEntityType<EngineBlockEntity> type = vineType;
            return type == null ? null : new EngineBlockEntity(type, pos, state);
        }
    }

    /** The engine's block entity: no behavior, just the attachment-carried payload. */
    public static final class EngineBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity {

        public EngineBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
            super(type, pos, state);
        }
    }

    /** The materialized block for {@code id}, or null when none was. */
    public static Block blockFor(VineId id) {
        return BLOCKS.get(id);
    }

    private static final java.util.Map<VineId, Block> BLOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Flagged blocks waiting for the block-entity pass, in materialization order. */
    private static final java.util.Map<VineId, EngineBlockEntityBlock> PENDING_BLOCK_ENTITIES =
        new java.util.LinkedHashMap<>();

    /**
     * Fills the vanilla block-entity registry for every flagged block
     * ({@code RegisterEvent} for {@code Registries.BLOCK_ENTITY_TYPE}, which fires
     * after the block pass).
     */
    public static void registerBlockEntities(RegisterEvent event,
            StructuralRegistryView.StructuralType type) {
        event.register(Registries.BLOCK_ENTITY_TYPE, helper -> {
            for (Holder<?> holder : type.entries()) {
                BlockDescriptor descriptor = (BlockDescriptor) holder.value();
                if (!descriptor.blockEntity()) {
                    continue;
                }
                EngineBlockEntityBlock block = PENDING_BLOCK_ENTITIES.get(descriptor.id());
                if (block == null) {
                    LOG.warn("vine: block entity requested for {} before its block materialized — skipped",
                        descriptor.id());
                    continue;
                }
                net.minecraft.world.level.block.entity.BlockEntityType<EngineBlockEntity> beType =
                    net.minecraft.world.level.block.entity.BlockEntityType.Builder
                        .of((pos, state) -> new EngineBlockEntity(block.vineType, pos, state), block)
                        .build(null);
                helper.register(location(descriptor.id()), beType);
                block.vineType = beType;
                dev.vineengine.vine.internal.driver1211.common.data.VineBlockEntity
                    .register(descriptor.id(), beType);
                LOG.info("vine: materialized block entity type for {} (NeoForge)", descriptor.id());
            }
        });
    }

    /** Materialized items by descriptor id — the voxel probe's attach targets. */
    private static final java.util.Map<VineId, Item> ITEMS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The item materialized for {@code id}, or null when none was. */
    public static Item itemFor(VineId id) {
        return ITEMS.get(id);
    }

    /** Registers every {@code vine:item} entry into the vanilla item registry. */
    public static void registerItems(RegisterEvent event, StructuralRegistryView.StructuralType type) {
        event.register(Registries.ITEM, helper -> {
            for (Holder<?> holder : type.entries()) {
                ItemDescriptor descriptor = (ItemDescriptor) holder.value();
                requireMatchingIds(holder, descriptor.id());
                Item item = new Item(new Item.Properties()
                    .stacksTo(descriptor.tuning().stackSize()));
                helper.register(location(descriptor.id()), item);
                ITEMS.put(holder.id(), item);
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
