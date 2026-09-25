package dev.vineengine.vine.internal.driver1211.neoforge.registry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.data.BlockEntityTarget;
import dev.vineengine.vine.entity.AttributeSpec;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.internal.content.BehaviorDispatch;
import dev.vineengine.vine.internal.driver1211.common.registry.RegistryHookTap;
import dev.vineengine.vine.internal.driver1211.neoforge.entity.VineEntity;
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
 * per ItemDescriptor. Behavior composition (sub-07 Stage C) is wired from the
 * block's own plan: each descriptor asks {@code BehaviorDispatch.plan} once here,
 * the answer rides the native block, and {@link NeoForgeBehaviorWiring} is what
 * that answer arms — a block that declares no behavior gets no ticker, no
 * interaction hook and no drop path (Minimal Footprint). A flagged descriptor's
 * block materializes with a block-entity type carrying the engine storage.
 *
 * <p>Sub-08 Stage A extends the same routing to {@code vine:entity}: one native
 * {@link VineEntity} type per descriptor, registered at the ENTITY_TYPE event, with
 * the descriptor's dimensions, eye height and attribute bases; the attribute
 * container itself binds at {@link EntityAttributeCreationEvent}, NeoForge's own
 * attribute-creation moment (see {@link #registerEntities}).
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
                // The engine's plan is what this cell wires for the block (sub-07
                // Stage C): read once, at materialization, so the native hooks answer
                // from the same decision the boot line counts.
                BehaviorDispatch.Plan plan = BehaviorDispatch.plan(descriptor.id());
                if (plan.ticking()) {
                    NeoForgeBehaviorWiring.installedTicker();
                }
                if (plan.use()) {
                    NeoForgeBehaviorWiring.installedUseHook();
                }
                if (plan.loot()) {
                    NeoForgeBehaviorWiring.installedLootPath();
                }
                BlockBehaviour.Properties properties = BlockBehaviour.Properties.of()
                    .strength(descriptor.tuning().hardness(), descriptor.tuning().resistance());
                Block block;
                if (descriptor.blockEntity().isPresent()) {
                    // A flagged block gets a block-entity type whose instances are
                    // the engine carrier (sub-07 Stage C, minimal): the payload rides
                    // NeoForge's attachment, so persistence applies with no behavior
                    // code. The type itself is registered in ITS OWN registry's
                    // pass ({@link #registerBlockEntities}) — NeoForge forbids
                    // nesting one registry's registration inside another's, and the
                    // block must exist first anyway.
                    EngineBlockEntityBlock engineBlock = EngineBlockEntityBlock.materialize(descriptor, properties, plan);
                    block = engineBlock;
                    PENDING_BLOCK_ENTITIES.put(descriptor.id(), engineBlock);
                } else {
                    block = EngineBlock.materialize(descriptor, properties, plan);
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
     *
     * <p>Sub-07 Stage C: the block also carries the engine's behavior plan, which is
     * the one decision its native hooks answer from, and it is where a declared use
     * behavior runs.
     */
    public static class EngineBlock extends Block {

        private final VineId id;
        private final BehaviorDispatch.Plan vinePlan;

        EngineBlock(BlockBehaviour.Properties properties, VineId id, BehaviorDispatch.Plan plan) {
            super(properties);
            this.id = id;
            this.vinePlan = plan;
        }

        /** Constructs the block for {@code descriptor}, its state model installed. */
        static EngineBlock materialize(BlockDescriptor descriptor, BlockBehaviour.Properties properties,
                BehaviorDispatch.Plan plan) {
            return NeoForgeBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlock(properties, descriptor.id(), plan));
        }

        @Override
        protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
            NeoForgeBlockStates.installStateModel(builder);
        }

        /**
         * The native block-interaction hook (sub-07 Stage C): the one vanilla layer a
         * real player's right-click ({@code ServerPlayerGameMode#useItemOn} →
         * {@code BlockState#useItemOn}) and NF's own interaction helper
         * ({@code GameTestHelper#useBlock}) both reach, carrying the hand, the face and
         * the hit point the engine's context promises.
         *
         * <p>Only a block whose plan declares a use behavior leaves vanilla's own
         * answer: a block that declares none returns {@code super}, i.e. the stage's
         * Minimal Footprint rule, untouched interaction included.
         */
        @Override
        protected ItemInteractionResult useItemOn(ItemStack stack, BlockState nativeState, Level level, BlockPos pos,
                Player player, InteractionHand hand, BlockHitResult hit) {
            if (!vinePlan.use()) {
                return super.useItemOn(stack, nativeState, level, pos, player, hand, hit);
            }
            return NeoForgeBehaviorWiring.useBlock(this, nativeState, level, pos, player, hand, hit);
        }

        /** The engine block descriptor this native block was materialized for. */
        public VineId vineId() {
            return id;
        }

        /** The behavior plan this block was materialized with — what its native hooks are armed for. */
        BehaviorDispatch.Plan vinePlan() {
            return vinePlan;
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

        EngineBlockEntityBlock(BlockBehaviour.Properties properties, VineId id, BehaviorDispatch.Plan plan) {
            super(properties, id, plan);
        }

        /** Constructs the block for a flagged {@code descriptor}, its state model installed. */
        static EngineBlockEntityBlock materialize(BlockDescriptor descriptor, BlockBehaviour.Properties properties,
                BehaviorDispatch.Plan plan) {
            return NeoForgeBlockStates.withStateModel(descriptor.properties(),
                () -> new EngineBlockEntityBlock(properties, descriptor.id(), plan));
        }

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(net.minecraft.core.BlockPos pos,
                BlockState state) {
            net.minecraft.world.level.block.entity.BlockEntityType<EngineBlockEntity> type = vineType;
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
        public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                BlockEntityType<T> type) {
            if (level.isClientSide() || !vinePlan().ticking() || type != vineType) {
                return null;
            }
            // The comparison above proves T is this block's own holder type, which is
            // what vanilla asks with (the block entity's own type).
            return (BlockEntityTicker<T>) (BlockEntityTicker<EngineBlockEntity>) NeoForgeBehaviorWiring::tickBlockEntity;
        }
    }

    /**
     * The engine's block entity: the attachment-carried payload plus the little state a
     * ticker needs (sub-07 Stage C) — the per-holder interval counter, and the holder's
     * own attach point, built once so a tick allocates neither.
     */
    public static final class EngineBlockEntity extends net.minecraft.world.level.block.entity.BlockEntity {

        /** Ticks since this holder's last dispatch — the interval is per holder, not per world clock. */
        int vineTicks;

        private BlockEntityTarget vineTarget;

        public EngineBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState state) {
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
                if (descriptor.blockEntity().isEmpty()) {
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

    // ------------------------------------------------------------------
    // Entities (sub-08 Stage A)
    // ------------------------------------------------------------------

    /**
     * Client-tracking range every materialized entity type gets: the value vanilla
     * gives its own large mobs (cow, iron golem, ravager), wide enough that a
     * 2.4-block descriptor entity does not de-render before a client can see it.
     */
    private static final int ENTITY_TRACKING_RANGE = 10;

    /** Materialized entity types by descriptor id — what the cell's {@code EntityDriver} resolves. */
    private static final java.util.Map<VineId, EntityType<?>> ENTITY_TYPES =
        new java.util.concurrent.ConcurrentHashMap<>();

    /** The native entity type materialized for {@code id}, or null when none was. */
    public static EntityType<?> entityTypeFor(VineId id) {
        return ENTITY_TYPES.get(id);
    }

    /**
     * Built attribute containers, keyed by the type they belong to, waiting for
     * NeoForge's {@code EntityAttributeCreationEvent} firing — the one moment a
     * container may be installed (firing after every {@code RegisterEvent} and
     * before common setup, so the attribute registry is complete when it is read).
     */
    private static final java.util.Map<EntityType<? extends LivingEntity>, AttributeSupplier>
        PENDING_ENTITY_ATTRIBUTES = new java.util.LinkedHashMap<>();

    /**
     * Registers every {@code vine:entity} entry into the vanilla entity-type
     * registry (sub-08 Stage A), one {@link VineEntity} native kind per descriptor,
     * and queues its attribute container for
     * {@link #registerEntityAttributes(EntityAttributeCreationEvent)}.
     *
     * <p><b>What the descriptor decides:</b> the type's dimensions and eye height
     * come from {@code Dimensions} — the eye height is set explicitly, never left at
     * vanilla's {@code height * 0.85} ratio, because the engine's line of sight and
     * part offsets were authored against the descriptor's number. The descriptor's
     * attributes become the type's default container, each base clamped into its
     * {@code [min, max]}, and the descriptor's modifiers are installed per instance
     * by {@link VineEntity}.
     *
     * <p><b>Spawn group MISC:</b> the group gates this cell's natural spawn cycle
     * (mob caps, biome spawn lists), and engine content is never part of that cycle —
     * {@code MISC} is vanilla's group for the kinds that exist only because something
     * created them.
     *
     * <p>Each attribute id resolves through the native attribute registry through
     * {@link #attributeHolder}; an id the registry does not know fails the boot naming
     * it rather than materializing an entity that silently lacks a stat. One line per
     * entity is logged, because the boot trace and the acceptance scenario both read
     * these base values back.
     */
    public static void registerEntities(RegisterEvent event, StructuralRegistryView.StructuralType type) {
        event.register(Registries.ENTITY_TYPE, helper -> {
            for (Holder<?> holder : type.entries()) {
                EntityDescriptor descriptor = (EntityDescriptor) holder.value();
                requireMatchingIds(holder, descriptor.id());
                ResourceLocation location = location(descriptor.id());
                EntityType<VineEntity> entityType = EntityType.Builder
                    .<VineEntity>of((nativeType, nativeLevel) ->
                        new VineEntity(nativeType, nativeLevel, descriptor.id(), descriptor), MobCategory.MISC)
                    .sized((float) descriptor.dimensions().width(), (float) descriptor.dimensions().height())
                    .eyeHeight((float) descriptor.dimensions().eyeHeight())
                    .clientTrackingRange(ENTITY_TRACKING_RANGE)
                    .build(location.toString());
                helper.register(location, entityType);
                ENTITY_TYPES.put(descriptor.id(), entityType);
                // The container is built now, while the descriptor that names its
                // bases is in hand, and installed at the attribute event below — the
                // pass NeoForge reserves for it.
                PENDING_ENTITY_ATTRIBUTES.put(entityType, defaultAttributes(descriptor));
                RegistryHookTap.dispatch(type.type().registryId().toString(), descriptor.id().toString());
                LOG.info("vine: materialized entity {} {}", descriptor.id(),
                    describeAttributes(descriptor.attributes(), AttributeSpec::base));
            }
        });
    }

    /**
     * Installs every materialized entity type's attribute container at NeoForge's
     * attribute-creation event (sub-08 Stage A) — after all registrations, before
     * common setup, and the only moment vanilla accepts a default container for a
     * type. A type without a container would throw from vanilla's own attribute
     * lookup on its first tick, so this drain is that failure's prevention, not a
     * convenience.
     */
    public static void registerEntityAttributes(EntityAttributeCreationEvent event) {
        for (java.util.Map.Entry<EntityType<? extends LivingEntity>, AttributeSupplier> pending
                : PENDING_ENTITY_ATTRIBUTES.entrySet()) {
            event.put(pending.getKey(), pending.getValue());
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
    private static AttributeSupplier defaultAttributes(EntityDescriptor descriptor) {
        AttributeSupplier.Builder builder = Mob.createMobAttributes();
        for (AttributeSpec spec : descriptor.attributes()) {
            // AttributeSpec's own invariant is min <= base <= max, so this clamp is
            // the descriptor contract made explicit, not a correction.
            double base = Math.min(spec.max(), Math.max(spec.min(), spec.base()));
            builder.add(attributeHolder(spec.attribute()), base);
        }
        return builder.build();
    }

    /**
     * The native attribute an engine attribute id names, resolved through the native
     * registry — the one authority on attribute identity (§5.13: the descriptor names
     * the native id, the cell resolves it). An id the registry does not know fails
     * the boot naming it, so a descriptor can never materialize an entity that
     * silently lacks a stat.
     */
    public static net.minecraft.core.Holder<Attribute> attributeHolder(VineId attribute) {
        return BuiltInRegistries.ATTRIBUTE.getHolder(location(attribute))
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
    public static String describeAttributes(java.util.List<AttributeSpec> specs,
            java.util.function.ToDoubleFunction<AttributeSpec> value) {
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

    private static ResourceLocation location(VineId id) {
        return ResourceLocation.fromNamespaceAndPath(id.namespace(), id.path());
    }
}
