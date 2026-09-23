package dev.vineengine.vine.testmod.registry;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Descriptor data for the testmod's structural marker type (sub-02 Stage B
 * exemplar — deliberately a marker, not a block; blocks are sub-07's surface).
 *
 * <p>Why this shape: the smallest record with two typed fields exercises the
 * full Codec path (Java authoring now, JSON authoring and network sync as
 * their stages land) without pretending to be game content.
 */
public record Testmarker(String label, int weight) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<Testmarker> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("label").forGetter(Testmarker::label),
        Codec.INT.fieldOf("weight").forGetter(Testmarker::weight)
    ).apply(instance, Testmarker::new));
}
