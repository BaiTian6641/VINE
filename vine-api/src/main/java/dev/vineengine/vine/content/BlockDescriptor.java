package dev.vineengine.vine.content;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One VINE block as data (sub-07 Stage A skeleton): identity, minimal physical
 * tuning, and the placeholder model hint. Properties/states, behaviors and the
 * {@code VoxelData}-backed block entity arrive in Stages B–C — a Stage-A
 * descriptor materializes into exactly one default-state native block.
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
 */
public record BlockDescriptor(VineId id, BlockTuning tuning, ModelHint model) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<BlockDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ContentCodecs.VINE_ID.fieldOf("id").forGetter(BlockDescriptor::id),
        BlockTuning.CODEC.fieldOf("tuning").forGetter(BlockDescriptor::tuning),
        ModelHint.CODEC.fieldOf("model").forGetter(BlockDescriptor::model)
    ).apply(instance, BlockDescriptor::new));

    public BlockDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tuning, "tuning");
        Objects.requireNonNull(model, "model");
    }
}
