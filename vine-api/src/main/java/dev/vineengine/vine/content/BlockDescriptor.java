package dev.vineengine.vine.content;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One VINE block as data (sub-07 Stage A skeleton): identity, minimal physical
 * tuning, and the placeholder model hint. Property/state flattening and behavior
 * composition arrive in Stages B–C; the engine-storage half of a block entity is
 * available now through {@link #blockEntity()} — a flagged block materializes
 * with a block-entity type whose payload is the engine bundle (sub-03), so
 * persistence, sync and the five attach points apply without a behavior language
 * existing yet.
 *
 * <p>Behavior composition over inheritance (§5.3): this is pure data plus
 * (later) behavior interfaces; the driver constructs the native singleton
 * delegate, so no class hierarchy crosses the API boundary.
 *
 * <p><b>Invariants:</b> the descriptor's {@link #id()} must equal the id it is
 * registered under — drivers reject a mismatch explicitly instead of guessing
 * which one owns the entry. Immutable; equality is identity-of-fields, never
 * of any runtime/native state.
 *
 * @param id      the block's engine id; also its native registry key
 * @param tuning  physical tuning materialized into native block properties
 * @param model   placeholder cooking hint; no client wiring before sub-16/17
 * @param blockEntity whether the block carries engine storage: materializing
 *        such a block also registers a block-entity type for it, and the
 *        entity's payload is the same {@code VoxelData} bundle attach point
 *        every other holder uses (sub-03/sub-07 Stage C) — a block with data,
 *        without a behavior language yet
 */
public record BlockDescriptor(VineId id, BlockTuning tuning, ModelHint model, boolean blockEntity) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<BlockDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ContentCodecs.VINE_ID.fieldOf("id").forGetter(BlockDescriptor::id),
        BlockTuning.CODEC.fieldOf("tuning").forGetter(BlockDescriptor::tuning),
        ModelHint.CODEC.fieldOf("model").forGetter(BlockDescriptor::model),
        // Defaulted so every descriptor authored before block entities existed
        // keeps its exact meaning (sub-02's structural JSON is data, not schema).
        Codec.BOOL.optionalFieldOf("blockEntity", false).forGetter(BlockDescriptor::blockEntity)
    ).apply(instance, BlockDescriptor::new));

    /** A block without engine storage — the Stage-A shape, unchanged. */
    public BlockDescriptor(VineId id, BlockTuning tuning, ModelHint model) {
        this(id, tuning, model, false);
    }

    public BlockDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tuning, "tuning");
        Objects.requireNonNull(model, "model");
    }
}
