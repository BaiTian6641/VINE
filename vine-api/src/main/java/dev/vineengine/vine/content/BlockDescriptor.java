package dev.vineengine.vine.content;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

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
 * @param id      the block's engine id; also its native registry key
 * @param properties the flattened state axes, in flattening order; empty means a
 *        single-state block (the Stage-A shape, unchanged)
 * @param tuning  physical tuning materialized into native block properties
 * @param blockEntity whether the block carries engine storage: materializing
 *        such a block also registers a block-entity type for it, and the
 *        entity's payload is the same {@code VoxelData} bundle attach point
 *        every other holder uses (sub-03/sub-07 Stage C) — a block with data,
 *        without a behavior language yet
 * @param model   placeholder cooking hint; no client wiring before sub-16/17
 */
public record BlockDescriptor(
        VineId id,
        List<Property<?>> properties,
        BlockTuning tuning,
        boolean blockEntity,
        ModelHint model) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<BlockDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ContentCodecs.VINE_ID.fieldOf("id").forGetter(BlockDescriptor::id),
        // Defaulted so every descriptor authored before Stage B keeps its exact
        // meaning (sub-02's structural JSON is data, not schema).
        Property.CODEC.listOf().optionalFieldOf("properties", List.of()).forGetter(BlockDescriptor::properties),
        BlockTuning.CODEC.fieldOf("tuning").forGetter(BlockDescriptor::tuning),
        Codec.BOOL.optionalFieldOf("blockEntity", false).forGetter(BlockDescriptor::blockEntity),
        ModelHint.CODEC.fieldOf("model").forGetter(BlockDescriptor::model)
    ).apply(instance, BlockDescriptor::new));

    /** A single-state block without engine storage — the Stage-A shape, unchanged. */
    public BlockDescriptor(VineId id, BlockTuning tuning, ModelHint model) {
        this(id, List.of(), tuning, false, model);
    }

    /** A single-state block, with or without engine storage — the Stage-C shape, unchanged. */
    public BlockDescriptor(VineId id, BlockTuning tuning, ModelHint model, boolean blockEntity) {
        this(id, List.of(), tuning, blockEntity, model);
    }

    /** A block with a declared state model and no engine storage. */
    public BlockDescriptor(VineId id, List<Property<?>> properties, BlockTuning tuning, ModelHint model) {
        this(id, properties, tuning, false, model);
    }

    public BlockDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tuning, "tuning");
        Objects.requireNonNull(model, "model");
        properties = List.copyOf(Objects.requireNonNull(properties, "properties"));
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
