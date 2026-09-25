package dev.vineengine.vine.entity;

import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One attribute of an entity descriptor (sub-08 Stage A): the value a fresh
 * entity starts with, the bounds it may move inside, and the modifiers applied on
 * top. The attribute is named by its <em>native</em> id — the same currency
 * vanilla's own attribute registry speaks — because attributes are the one entity
 * surface where a cell's own registry is the authority and the engine deliberately
 * does not shadow it (§5.13: engine attribute wrapper, native attribute store).
 *
 * <p><b>Invariants:</b> {@code min <= base <= max}; {@code max >= min}; modifier ids
 * are unique inside one spec, so two modifiers never fight over one slot
 * invisibly. Immutable.
 *
 * @param attribute the native attribute id (e.g. {@code minecraft:generic.max_health}
 *                  on 1.21.1; a cell whose registry renames it absorbs that in the
 *                  driver, never here)
 * @param base      the value a fresh entity starts with
 * @param min       the lower clamp the engine and the cell both respect
 * @param max       the upper clamp; {@code base <= max}
 * @param modifiers modifiers applied after the base value, in declaration order
 */
public record AttributeSpec(VineId attribute, double base, double min, double max,
        List<AttributeModifier> modifiers) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<AttributeSpec> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("attribute").forGetter(AttributeSpec::attribute),
        Codec.DOUBLE.fieldOf("base").forGetter(AttributeSpec::base),
        Codec.DOUBLE.optionalFieldOf("min", 0.0D).forGetter(AttributeSpec::min),
        Codec.DOUBLE.fieldOf("max").forGetter(AttributeSpec::max),
        AttributeModifier.CODEC.listOf().optionalFieldOf("modifiers", List.of()).forGetter(AttributeSpec::modifiers)
    ).apply(instance, AttributeSpec::new));

    /** A plain value with no modifiers, bounded by {@code [0, max]}. */
    public static AttributeSpec of(VineId attribute, double base, double max) {
        return new AttributeSpec(attribute, base, 0.0D, max, List.of());
    }

    /** The same spec with one more modifier. */
    public AttributeSpec with(AttributeModifier modifier) {
        Objects.requireNonNull(modifier, "modifier");
        List<AttributeModifier> extended = new java.util.ArrayList<>(modifiers);
        extended.add(modifier);
        return new AttributeSpec(attribute, base, min, max, List.copyOf(extended));
    }

    public AttributeSpec {
        Objects.requireNonNull(attribute, "attribute");
        modifiers = List.copyOf(Objects.requireNonNull(modifiers, "modifiers"));
        if (max < min) {
            throw new IllegalArgumentException("AttributeSpec " + attribute + ": max " + max + " < min " + min);
        }
        if (base < min || base > max) {
            throw new IllegalArgumentException("AttributeSpec " + attribute + ": base " + base + " outside ["
                + min + ", " + max + "]");
        }
        java.util.HashSet<VineId> ids = new java.util.HashSet<>(modifiers.size());
        for (AttributeModifier modifier : modifiers) {
            Objects.requireNonNull(modifier, "modifiers element");
            if (!ids.add(modifier.id())) {
                throw new IllegalArgumentException("AttributeSpec " + attribute + ": duplicate modifier id "
                    + modifier.id() + " — two modifiers on one slot cannot be told apart");
            }
        }
    }
}
