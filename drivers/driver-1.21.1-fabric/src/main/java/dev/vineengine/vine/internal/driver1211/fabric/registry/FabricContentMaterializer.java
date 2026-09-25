package dev.vineengine.vine.internal.driver1211.fabric.registry;

import java.util.List;
import java.util.function.ToDoubleFunction;

import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.StateManager;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.data.BlockEntityTarget;
import dev.vineengine.vine.entity.AttributeSpec;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.internal.content.BehaviorDispatch;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.driver1211.fabric.entity.VineEntity;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;

/**
 * Native block/item/entity materialization for the 1.21.1 Fabric cell (sub-07
 * Stage A; the entity kind is sub-08 Stage A): the sub-02 seam extension for
 * content kinds. Unlike generic structural types (which get a vine-created
 * registry), {@code vine:block}, {@code vine:item} and {@code vine:entity} entries
 * land in the <em>vanilla</em> registries — a placeable/breakable block, an
 * inventory-real item and a live entity only exist as vanilla singletons, so
 * {@code Registries.BLOCK}/{@code ITEM}/{@code ENTITY_TYPE} are the authority.
 *
 * <p>Loader difference absorbed (vs. NeoForge's per-registry
 * {@code RegisterEvent} phasing): Fabric's registration moment is plain
 * {@code Registry.register} during mod init, immediately after consumer
 * initializers have populated the descriptor store.
 *
 * <p>Shape: one block per BlockDescriptor (tuning mapped 1:1 onto
 * {@code AbstractBlock.Settings}) whose state manager is the descriptor's
 * flattened state model (sub-07 Stage B, {@link FabricBlockStates}), one item per
 * ItemDescriptor. Behavior composition (sub-07 Stage C) is wired from the block's
 * own plan: each descriptor asks {@code BehaviorDispatch.plan} once here, the answer
 * rides the native block, and {@link FabricBehaviorWiring} is what that answer arms
 * — a block that declares no behavior gets no ticker, no interaction hook and no
 * loot path (Minimal Footprint).
 *
 * <p>Sub-08 Stage A adds the entity kind: one {@link VineEntity} type per
 * EntityDescriptor, its dimensions and eye height from the descriptor, its
 * attributes bound through {@link FabricDefaultAttributeRegistry} and its
 * modifiers installed on each instance (see {@link #registerEntities}).
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
            // The engine's plan is what this cell wires for the block (sub-07 Stage C):
            // read once, at materialization, so the native hooks answer from the same
            // decision the boot line counts.
            BehaviorDispatch.Plan plan = BehaviorDispatch.plan(descriptor.id());
            if (plan.ticking()) {
                FabricBehaviorWiring.installedTicker();
            }
            if (plan.use()) {
                FabricBehaviorWiring.installedUseHook();
            }
            if (plan.loot()) {
                FabricBehaviorWiring.installedLootPath();
            }
            AbstractBlock.Settings settings = AbstractBlock.Settings.create()
                .strength(descriptor.tuning().hardness(), descriptor.tuning().resistance());
            Block block;
            if (descriptor.blockEntity().isPresent()) {
                // A flagged block gets a block-entity type whose instances are the
                // engine carrier (sub-07 Stage C, minimal): the payload rides the
                // cell's attachment, so persistence applies with no behavior code.
                // The block holds its type in a setter because the type's factory
                // needs the block — the one circularity the vanilla API forces.
                EngineBlockEntityBlock engineBlock = EngineBlockEntityBlock.materialize(descriptor, settings, plan);
                block = engineBlock;
                BlockEntityType<EngineBlockEntity> beType = BlockEntityType.Builder
                    .create((pos, state) -> new EngineBlockEntity(engineBlock.vineType, pos, state), block)
                    .build();
                Registry.register(Registries.BLOCK_ENTITY_TYPE, identifier(descriptor.id()), beType);
                engineBlock.vineType = beType;
                LOG.info("vine: materialized block entity type for {} (Fabric)", descriptor.id());
            } else {
                block = EngineBlock.materialize(descriptor, settings, plan);
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
     *
     * <p>Sub-07 Stage C: the block also carries the engine's behavior plan, which is
     * the one decision its native hooks answer from.
     */
    public static class EngineBlock extends Block {

        private final VineId id;
        private final BehaviorDispatch.Plan vinePlan;

        EngineBlock(AbstractBlock.Settings settings, VineId id, BehaviorDispatch.Plan plan) {
            super(settings);
            this.id = id;
            this.vinePlan = plan;
        }

        /** Constructs the block for {@code descriptor}, its state model installed. */
        static EngineBlock materialize(BlockDescriptor descriptor, AbstractBlock.Settings settings,
                BehaviorDispatch.Plan plan) {
            return FabricBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlock(settings, descriptor.id(), plan));
        }

        @Override
        protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
            FabricBlockStates.installStateModel(builder);
        }

        /**
         * The native block-interaction hook (sub-07 Stage C). Vanilla's
         * {@code ServerPlayerInteractionManager#interactBlock} calls this first — with
         * the hand, which the block-only {@code onUse} callback no longer carries — so
         * this is where an engine use behavior runs.
         *
         * <p>Only a block whose plan declares a use behavior leaves vanilla's own
         * answer: a block that declares none returns {@code super}, i.e. the stage's
         * Minimal Footprint rule, untouched interaction included.
         */
        @Override
        protected ItemActionResult onUseWithItem(ItemStack stack, BlockState state, World world,
                net.minecraft.util.math.BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
            if (!vinePlan.use()) {
                return super.onUseWithItem(stack, state, world, pos, player, hand, hit);
            }
            return FabricBehaviorWiring.useBlock(this, state, world, pos, player, hand, hit);
        }

        /** The engine block descriptor this native block was materialized for. */
        public VineId vineId() {
            return id;
        }

        /**
         * The native removal hook (sub-07 Stage C): where a block's loot behaviors run,
         * because this is the one native moment every removal passes through — a player's
         * break, an explosion, a piston, a world edit — and a {@code LootBehavior} modifies
         * what a block drops whatever removed it. Only a block whose plan declares a loot
         * behavior dispatches; every other block takes vanilla's own answer unchanged
         * (the {@code super} call, which is the only thing this override adds for them).
         *
         * <p>A state change that keeps the same block (a property flip) is not a removal:
         * nothing leaves the world, so nothing drops. Vanilla's own removal bookkeeping
         * runs first, so a throwing behavior cannot leave a block entity behind.
         */
        @Override
        protected void onStateReplaced(BlockState state, World world, net.minecraft.util.math.BlockPos pos,
                BlockState newState, boolean moved) {
            super.onStateReplaced(state, world, pos, newState, moved);
            if (vinePlan.loot() && !state.isOf(newState.getBlock())) {
                FabricBehaviorWiring.lootOnRemoval(world, pos, state);
            }
        }

        /** The behavior plan this block was materialized with — what its native hooks are armed for. */
        BehaviorDispatch.Plan vinePlan() {
            return vinePlan;
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

        volatile BlockEntityType<EngineBlockEntity> vineType;

        EngineBlockEntityBlock(AbstractBlock.Settings settings, VineId id, BehaviorDispatch.Plan plan) {
            super(settings, id, plan);
        }

        /** Constructs the block for a flagged {@code descriptor}, its state model installed. */
        static EngineBlockEntityBlock materialize(BlockDescriptor descriptor, AbstractBlock.Settings settings,
                BehaviorDispatch.Plan plan) {
            return FabricBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlockEntityBlock(settings, descriptor.id(), plan));
        }

        @Override
        public net.minecraft.block.entity.BlockEntity createBlockEntity(net.minecraft.util.math.BlockPos pos,
                net.minecraft.block.BlockState state) {
            BlockEntityType<EngineBlockEntity> type = vineType;
            return type == null ? null : new EngineBlockEntity(type, pos, state);
        }

        /**
         * The ticker vanilla installs when this holder enters the ticking set (sub-07
         * Stage C): present exactly when the plan ticks, so a holder whose descriptor
         * declares no ticking gets no ticker at all — the stage's boot assertion, made
         * native.
         *
         * <p>The client is not wired: holder payloads do not sync yet (sub-03 Stage E
         * lists that as remaining), so a client-side tick would run behaviors against a
         * tree the client never received.
         */
        @SuppressWarnings("unchecked")
        @Override
        public <T extends net.minecraft.block.entity.BlockEntity> BlockEntityTicker<T> getTicker(
                World world, BlockState state, BlockEntityType<T> type) {
            if (world.isClient() || !vinePlan().ticking() || type != vineType) {
                return null;
            }
            // The comparison above proves T is this block's own holder type, which is
            // what vanilla asks with (the block entity's own type).
            return (BlockEntityTicker<T>) (BlockEntityTicker<EngineBlockEntity>) FabricBehaviorWiring::tickBlockEntity;
        }
    }

    /**
     * The engine's block entity: the attachment-carried payload plus the little state a
     * ticker needs (sub-07 Stage C) — the per-holder interval counter, and the holder's
     * own attach point, built once so a tick allocates neither.
     */
    public static final class EngineBlockEntity extends net.minecraft.block.entity.BlockEntity {

        /** Ticks since this holder's last dispatch — the interval is per holder, not per world clock. */
        int vineTicks;

        private BlockEntityTarget vineTarget;

        public EngineBlockEntity(BlockEntityType<?> type, net.minecraft.util.math.BlockPos pos,
                net.minecraft.block.BlockState state) {
            super(type, pos, state);
        }

        /** This holder's engine attach point, built on first use and reused for its lifetime. */
        BlockEntityTarget vineTarget() {
            BlockEntityTarget current = vineTarget;
            if (current == null) {
                current = new BlockEntityTarget(this);
                vineTarget = current;
            }
            return current;
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

    // ------------------------------------------------------------------
    // Entities (sub-08 Stage A)
    // ------------------------------------------------------------------

    /** Materialized entity types by descriptor id — what the cell's {@code EntityDriver} resolves. */
    private static final java.util.Map<VineId, EntityType<?>> ENTITY_TYPES =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The native entity type materialized for {@code id}, or null when none was. */
    public static EntityType<?> entityTypeFor(VineId id) {
        return ENTITY_TYPES.get(id);
    }

    /**
     * Registers every {@code vine:entity} entry into the vanilla entity-type
     * registry (sub-08 Stage A), one {@link VineEntity} native kind per descriptor.
     *
     * <p><b>What the descriptor decides:</b> the type's dimensions and eye height
     * come from {@code Dimensions} — the eye height is set explicitly, never left at
     * this cell's {@code height * 0.85} default, because the engine's line of sight
     * and part offsets were authored against the descriptor's number. The
     * descriptor's attributes become the type's default container, each base clamped
     * into its {@code [min, max]}, and the descriptor's modifiers are installed per
     * instance by {@link VineEntity}.
     *
     * <p><b>Spawn group MISC:</b> the group gates this cell's natural spawn cycle
     * (mob caps, biome spawn lists), and engine content is never part of that cycle —
     * {@code MISC} is vanilla's group for the kinds that exist only because something
     * created them.
     *
     * <p>Each attribute id resolves through the native attribute registry through
     * {@link #attributeEntry}; an id the registry does not know fails the boot naming
     * it rather than materializing an entity that silently lacks a stat. One line per
     * entity is logged, because the boot trace and the acceptance scenario both read
     * these base values back.
     */
    public static void registerEntities(StructuralRegistryView.StructuralType type) {
        for (Holder<?> holder : type.entries()) {
            EntityDescriptor descriptor = (EntityDescriptor) holder.value();
            requireMatchingIds(holder, descriptor.id());
            Identifier location = identifier(descriptor.id());
            EntityType<VineEntity> entityType = EntityType.Builder
                .<VineEntity>create((nativeType, nativeWorld) ->
                    new VineEntity(nativeType, nativeWorld, descriptor.id(), descriptor), SpawnGroup.MISC)
                .dimensions((float) descriptor.dimensions().width(), (float) descriptor.dimensions().height())
                .eyeHeight((float) descriptor.dimensions().eyeHeight())
                .build(location.toString());
            Registry.register(Registries.ENTITY_TYPE, location, entityType);
            FabricDefaultAttributeRegistry.register(entityType, defaultAttributes(descriptor));
            ENTITY_TYPES.put(descriptor.id(), entityType);
            RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
            LOG.info("vine: materialized entity {} {}", descriptor.id(),
                describeAttributes(descriptor.attributes(), AttributeSpec::base));
        }
    }

    /**
     * The default attribute container for {@code descriptor}: the mob's own vanilla
     * default set as the floor, with every descriptor attribute overriding it.
     *
     * <p>The floor is deliberate: vanilla entity code reads attributes the descriptor
     * does not name (follow range today), and a container missing them would fail
     * inside a tick rather than at the descriptor author's keyboard. A descriptor
     * value wins wherever the descriptor speaks.
     */
    private static DefaultAttributeContainer.Builder defaultAttributes(EntityDescriptor descriptor) {
        DefaultAttributeContainer.Builder builder = MobEntity.createMobAttributes();
        for (AttributeSpec spec : descriptor.attributes()) {
            // AttributeSpec's own invariant is min <= base <= max, so this clamp is
            // the descriptor contract made explicit, not a correction.
            double base = Math.min(spec.max(), Math.max(spec.min(), spec.base()));
            builder.add(attributeEntry(spec.attribute()), base);
        }
        return builder;
    }

    /**
     * The native attribute an engine attribute id names, resolved through the native
     * registry — the one authority on attribute identity (§5.13: the descriptor names
     * the native id, the cell resolves it). An id the registry does not know fails
     * the boot naming it, so a descriptor can never materialize an entity that
     * silently lacks a stat.
     */
    public static RegistryEntry<EntityAttribute> attributeEntry(VineId attribute) {
        return Registries.ATTRIBUTE.getEntry(identifier(attribute))
            .orElseThrow(() -> new IllegalStateException("entity attribute " + attribute
                + " does not exist in this cell's attribute registry — the descriptor names a native"
                + " attribute this cell cannot bind"));
    }

    /**
     * Renders {@code label=value} pairs for {@code specs} in declaration order — the
     * one form both the materialization line (base values, {@link AttributeSpec#base})
     * and the spawn line (live instance values) use. The label is the attribute id's
     * final dot-segment, so {@code minecraft:generic.max_health} reads
     * {@code max_health}: the name the acceptance needles use.
     */
    public static String describeAttributes(List<AttributeSpec> specs, ToDoubleFunction<AttributeSpec> value) {
        java.util.List<String> parts = new java.util.ArrayList<>(specs.size());
        for (AttributeSpec spec : specs) {
            String path = spec.attribute().path();
            int dot = path.lastIndexOf('.');
            parts.add((dot < 0 ? path : path.substring(dot + 1)) + "=" + value.applyAsDouble(spec));
        }
        return String.join(" ", parts);
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
