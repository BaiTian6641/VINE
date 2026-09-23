package dev.vineengine.vine.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Minimal per-block physical tuning carried by a {@link BlockDescriptor}
 * (sub-07 Stage A — hardness and resistance only; tool/tier hints, light,
 * friction and the rest of the §5.3 set land with later stages).
 *
 * <p>Values map 1:1 onto the vanilla block-properties pair every cell exposes
 * post-1.13, so no driver branch is needed and behavior is identical across
 * loaders by construction.
 *
 * @param hardness   vanilla destroy time; {@code -1} means unbreakable
 *                   (bedrock semantics — the only legal negative)
 * @param resistance vanilla explosion resistance; never negative
 */
public record BlockTuning(float hardness, float resistance) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<BlockTuning> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.FLOAT.fieldOf("hardness").forGetter(BlockTuning::hardness),
        Codec.FLOAT.fieldOf("resistance").forGetter(BlockTuning::resistance)
    ).apply(instance, BlockTuning::new));

    /** Stone-flavoured default; authored explicitly rather than hidden in the codec. */
    public static final BlockTuning STONE_LIKE = new BlockTuning(1.5F, 6.0F);

    public BlockTuning {
        if (!(hardness >= 0.0F || hardness == -1.0F)) {
            throw new IllegalArgumentException(
                "BlockTuning.hardness must be >= 0, or exactly -1 for unbreakable: " + hardness);
        }
        if (!(resistance >= 0.0F)) {
            throw new IllegalArgumentException("BlockTuning.resistance must be >= 0: " + resistance);
        }
    }
}
