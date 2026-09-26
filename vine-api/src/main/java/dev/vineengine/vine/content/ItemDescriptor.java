package dev.vineengine.vine.content;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One VINE item as data (sub-07 Stage A skeleton): identity, minimal tuning,
 * and the placeholder model hint. Durability-as-data, the behavior hook
 * family and creative-tab membership arrive in Stages D–E.
 *
 * <p><b>Invariants:</b> durability is never part of this descriptor or of
 * item identity — damage lives on the item stack as {@code VoxelData} (sub-03,
 * key {@code vine:durability}) once Stage D wires it (§5.3/§5.4). The
 * descriptor's {@link #id()} must equal the id it is registered under.
 * Immutable.
 *
 * @param id     the item's engine id; also its native registry key
 * @param tuning stack-size tuning materialized into native item properties
 * @param model  placeholder cooking hint; no client wiring before sub-16/17
 * @param combat the item's combat declaration (sub-10), or empty for an item that deals
 *               no engine melee damage — the engine never guesses that an item is a weapon
 */
public record ItemDescriptor(VineId id, ItemTuning tuning, ModelHint model,
        java.util.Optional<dev.vineengine.vine.combat.CombatProfile> combat) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ItemDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(ItemDescriptor::id),
        ItemTuning.CODEC.fieldOf("tuning").forGetter(ItemDescriptor::tuning),
        ModelHint.CODEC.fieldOf("model").forGetter(ItemDescriptor::model),
        dev.vineengine.vine.combat.CombatProfile.CODEC.optionalFieldOf("combat")
            .forGetter(ItemDescriptor::combat)
    ).apply(instance, ItemDescriptor::new));

    public ItemDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tuning, "tuning");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(combat, "combat");
    }

    /** An item with no combat declaration — the common case. */
    public ItemDescriptor(VineId id, ItemTuning tuning, ModelHint model) {
        this(id, tuning, model, java.util.Optional.empty());
    }
}
