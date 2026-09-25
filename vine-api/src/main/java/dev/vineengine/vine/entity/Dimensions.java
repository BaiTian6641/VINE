package dev.vineengine.vine.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * An entity's physical size (sub-08 Stage A): the box a cell gives the entity's
 * hitbox and collision, plus where its eyes sit inside it. Engine-owned because
 * the numbers drive gameplay (reach, line of sight, part offsets) before any cell
 * sees them.
 *
 * <p><b>Invariants:</b> width and height are positive; the eye height is inside
 * {@code [0, height]}. Immutable.
 *
 * @param width     the box's horizontal size, in blocks
 * @param height    the box's vertical size, in blocks
 * @param eyeHeight how far above the entity's feet its eyes sit, in blocks
 */
public record Dimensions(double width, double height, double eyeHeight) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<Dimensions> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.DOUBLE.fieldOf("width").forGetter(Dimensions::width),
        Codec.DOUBLE.fieldOf("height").forGetter(Dimensions::height),
        Codec.DOUBLE.optionalFieldOf("eyeHeight", -1.0D).forGetter(Dimensions::eyeHeight)
    ).apply(instance, Dimensions::new));

    /** Human-scale default: 0.6 × 1.8 with eyes at 1.62 (vanilla's player box). */
    public static final Dimensions PLAYER_LIKE = new Dimensions(0.6D, 1.8D, 1.62D);

    /** A box whose eyes sit at 85% of its height — the convention every mob uses. */
    public static Dimensions of(double width, double height) {
        return new Dimensions(width, height, height * 0.85D);
    }

    public Dimensions {
        if (eyeHeight < 0.0D) {
            eyeHeight = height * 0.85D;
        }
        if (!(width > 0.0D)) {
            throw new IllegalArgumentException("Dimensions.width must be positive: " + width);
        }
        if (!(height > 0.0D)) {
            throw new IllegalArgumentException("Dimensions.height must be positive: " + height);
        }
        if (eyeHeight < 0.0D || eyeHeight > height) {
            throw new IllegalArgumentException("Dimensions.eyeHeight must be inside [0, height] (" + eyeHeight
                + " not in [0, " + height + "])");
        }
    }
}
