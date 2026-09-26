package dev.vineengine.vine.ui;

import com.mojang.serialization.Codec;

/**
 * Where a widget's rectangle is measured from (sub-16 §2). The engine resolves layout in
 * logical pixels against a screen size and a GUI scale the <em>cell</em> reports — a
 * consumer never reads the scale, because a consumer that does is a consumer that breaks on
 * someone else's monitor.
 */
public enum Anchor {

    /** Measured from the top-left corner; the usual choice for a panel. */
    TOP_LEFT,

    /** Measured from the top edge, centred horizontally. */
    TOP_CENTER,

    /** Measured from the top-right corner: {@code x} counts leftwards. */
    TOP_RIGHT,

    /** Centred on both axes; the usual choice for a title card. */
    CENTER,

    /** Measured from the bottom-left corner: {@code y} counts upwards. */
    BOTTOM_LEFT;

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<Anchor> CODEC = Codec.STRING.xmap(
        name -> Anchor.valueOf(name.toUpperCase(java.util.Locale.ROOT)),
        anchor -> anchor.name().toLowerCase(java.util.Locale.ROOT));
}
