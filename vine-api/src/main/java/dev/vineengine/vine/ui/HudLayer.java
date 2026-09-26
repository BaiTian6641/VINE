package dev.vineengine.vine.ui;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * A HUD layer (sub-16 §2): the engine owns the layer order and a per-player visibility flag,
 * a cell draws what it is told. Ordering is explicit rather than registration-order, because
 * a health bar that lands under a chat line is a bug nobody can reproduce on purpose.
 *
 * @param id             the layer's engine id
 * @param anchor         where it sits
 * @param zOrder         draw order; higher draws later
 * @param defaultVisible whether a player sees it before touching anything
 */
public record HudLayer(VineId id, Anchor anchor, int zOrder, boolean defaultVisible) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<HudLayer> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(HudLayer::id),
        Anchor.CODEC.optionalFieldOf("anchor", Anchor.TOP_LEFT).forGetter(HudLayer::anchor),
        Codec.INT.optionalFieldOf("zOrder", 0).forGetter(HudLayer::zOrder),
        Codec.BOOL.optionalFieldOf("defaultVisible", true).forGetter(HudLayer::defaultVisible)
    ).apply(instance, HudLayer::new));

    public HudLayer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(anchor, "anchor");
    }
}
