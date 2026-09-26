package dev.vineengine.vine.ui;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * A widget's rectangle in logical pixels (sub-16 §2): where it sits relative to its
 * {@link Anchor}, and how big it is. The engine's solver turns this into absolute
 * coordinates for a given screen size and GUI scale; the widget never computes its own
 * position, so two cells cannot disagree about where a button is.
 *
 * @param x      horizontal offset from the anchor
 * @param y      vertical offset from the anchor
 * @param width  width in logical pixels
 * @param height height in logical pixels
 * @param anchor what the offsets are measured from
 */
public record LayoutSpec(int x, int y, int width, int height, Anchor anchor) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<LayoutSpec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.optionalFieldOf("x", 0).forGetter(LayoutSpec::x),
        Codec.INT.optionalFieldOf("y", 0).forGetter(LayoutSpec::y),
        Codec.INT.fieldOf("width").forGetter(LayoutSpec::width),
        Codec.INT.fieldOf("height").forGetter(LayoutSpec::height),
        Anchor.CODEC.optionalFieldOf("anchor", Anchor.TOP_LEFT).forGetter(LayoutSpec::anchor)
    ).apply(instance, LayoutSpec::new));

    public LayoutSpec {
        Objects.requireNonNull(anchor, "anchor");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("LayoutSpec: width and height must be positive, got " + width + "x"
                + height);
        }
    }

    /** A widget at an absolute offset from the top-left corner. */
    public static LayoutSpec at(int x, int y, int width, int height) {
        return new LayoutSpec(x, y, width, height, Anchor.TOP_LEFT);
    }
}
