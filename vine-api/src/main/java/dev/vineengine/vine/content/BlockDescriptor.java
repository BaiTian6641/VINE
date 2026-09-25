package dev.vineengine.vine.content;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One VINE block as data: identity, its flattened state model, minimal physical
 * tuning and the placeholder model hint. Behavior composition arrives in
 * Stage C; the engine-storage half of a block entity is available now through
 * {@link #blockEntity()} — a flagged block materializes with a block-entity type
 * whose payload is the engine bundle (sub-03), so persistence, sync and the five
 * attach points apply without a behavior language existing yet.
 *
 * <p><b>Flattened state model (sub-07 Stage B, plan §5.3):</b>
 * {@link #properties()} is the <em>only</em> state model on every supported cell
 * (all of them post-1.13), so a descriptor never declares a legacy metadata
 * path. The product of the declared value lists is the block's state table; the
 * engine rejects a descriptor whose product exceeds its state budget at
 * registration time with a diagnostic naming the terms (vine-core's
 * {@code BlockStateTable}), because an over-budget block cannot be carried by
 * any cell. The default state is the first declared value of each property —
 * the same rule the cells materialize natively, so engine and native default
 * states are identical by construction.
 *
 * <p>Behavior composition over inheritance (§5.3): this is pure data plus
 * (later) behavior interfaces; the driver constructs the native singleton
 * delegate, so no class hierarchy crosses the API boundary.
 *
 * <p><b>Invariants:</b> the descriptor's {@link #id()} must equal the id it is
 * registered under — drivers reject a mismatch explicitly instead of guessing
 * which one owns the entry; property names are unique inside one descriptor
 * (they are the keys of its state string). Immutable; equality is
 * identity-of-fields, never of any runtime/native state.
 *
 * <p><b>Behavior composition (sub-07 Stage C):</b> {@link #behaviors()} is an
 * ordered list; a cell wires the native dispatch a behavior family can serve only
 * when at least one behavior of that family is present, and wires nothing when the
 * list is empty (Minimal Footprint). Behavior instances are stateless with respect to
 * the engine — whatever a holder must remember lives in its {@code VoxelData} tree.
 *
 * @param id      the block's engine id; also its native registry key
 * @param properties the flattened state axes, in flattening order; empty means a
 *        single-state block (the Stage-A shape, unchanged)
 * @param tuning  physical tuning materialized into native block properties
 * @param blockEntity the block's engine storage declaration, or empty for a plain
 *        block: materializing a declared block also registers a block-entity type
 *        for it, and the entity's payload is the same {@code VoxelData} bundle
 *        attach point every other holder uses (sub-03/sub-07 Stage C) — the schema
 *        named there is what the holder's tree must satisfy, and its
 *        {@code ticking} flag is what decides whether any cell registers a ticker
 * @param behaviors the ordered behavior list; empty installs nothing anywhere
 * @param model   placeholder cooking hint; no client wiring before sub-16/17
 */
public record BlockDescriptor(
        VineId id,
        List<Property<?>> properties,
        BlockTuning tuning,
        Optional<BlockEntityDescriptor> blockEntity,
        List<BlockBehavior> behaviors,
        ModelHint model) {

    /**
     * Single source of truth for every representation of <em>data</em> (sub-02 §2).
     *
     * <p>Behaviors are deliberately absent: a behavior is code (an implementation of
     * a family interface), and a JSON form for it would need a registry of behavior
     * types and their codecs — a surface no stage has built yet. Decoding therefore
     * yields a descriptor with an empty behavior list, and an author who needs
     * behaviors supplies them from Java; the alternative (a string field that decodes
     * to nothing) would be a lie in the data format.
     */
    public static final Codec<BlockDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ContentCodecs.VINE_ID.fieldOf("id").forGetter(BlockDescriptor::id),
        // Defaulted so every descriptor authored before Stage B keeps its exact
        // meaning (sub-02's structural JSON is data, not schema).
        Property.CODEC.listOf().optionalFieldOf("properties", List.of()).forGetter(BlockDescriptor::properties),
        BlockTuning.CODEC.fieldOf("tuning").forGetter(BlockDescriptor::tuning),
        BlockEntityDescriptor.CODEC.optionalFieldOf("blockEntity").forGetter(BlockDescriptor::blockEntity),
        ModelHint.CODEC.fieldOf("model").forGetter(BlockDescriptor::model)
    ).apply(instance, (id, properties, tuning, blockEntity, model) ->
        new BlockDescriptor(id, properties, tuning, blockEntity, List.of(), model)));

    /** A single-state block without engine storage — the Stage-A shape, unchanged. */
    public BlockDescriptor(VineId id, BlockTuning tuning, ModelHint model) {
        this(id, List.of(), tuning, Optional.empty(), List.of(), model);
    }

    /** A single-state block with engine storage and no behaviors. */
    public BlockDescriptor(VineId id, BlockTuning tuning, ModelHint model, BlockEntityDescriptor blockEntity) {
        this(id, List.of(), tuning, Optional.of(blockEntity), List.of(), model);
    }

    /** A block with a declared state model and no engine storage. */
    public BlockDescriptor(VineId id, List<Property<?>> properties, BlockTuning tuning, ModelHint model) {
        this(id, properties, tuning, Optional.empty(), List.of(), model);
    }

    /** A block with a declared state model, engine storage and behaviors. */
    public BlockDescriptor(VineId id, List<Property<?>> properties, BlockTuning tuning,
            BlockEntityDescriptor blockEntity, List<BlockBehavior> behaviors, ModelHint model) {
        this(id, properties, tuning, Optional.of(blockEntity), behaviors, model);
    }

    public BlockDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tuning, "tuning");
        Objects.requireNonNull(model, "model");
        properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
        blockEntity = Objects.requireNonNull(blockEntity, "blockEntity");
        behaviors = List.copyOf(Objects.requireNonNull(behaviors, "behaviors"));
        for (BlockBehavior behavior : behaviors) {
            Objects.requireNonNull(behavior, "behaviors element");
        }
        HashSet<String> names = new HashSet<>(properties.size());
        for (Property<?> property : properties) {
            Objects.requireNonNull(property, "properties element");
            if (!names.add(property.name())) {
                throw new IllegalArgumentException("BlockDescriptor " + id + ": duplicate property name '"
                    + property.name() + "' — property names are the keys of the state string");
            }
        }
    }
}
