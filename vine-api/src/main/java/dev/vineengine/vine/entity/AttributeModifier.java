package dev.vineengine.vine.entity;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One attribute modifier on an entity descriptor (sub-08 Stage A): a named,
 * removable change to an attribute, with the three operation kinds every cell's
 * attribute system already has (add, multiply the base, multiply the total).
 *
 * <p>The id exists so a modifier can be <em>removed</em> later (equipment,
 * buffs, wounds): the engine's rule is that anything a descriptor applies must be
 * addressable by name, or a consumer can never take it back off.
 *
 * @param id       the modifier's engine id, unique inside its attribute spec
 * @param operation how the amount combines with the value
 * @param amount   the modifier's magnitude
 */
public record AttributeModifier(VineId id, Operation operation, double amount) {

    /** The three operations every supported cell exposes, in vanilla's own terms. */
    public enum Operation {

        /** {@code value + amount}. */
        ADD,

        /** {@code value + base * amount}. */
        MULTIPLY_BASE,

        /** {@code value * (1 + amount)}. */
        MULTIPLY_TOTAL
    }

    private static final Codec<Operation> OPERATION = Codec.STRING.comapFlatMap(
        name -> {
            try {
                return DataResult.success(Operation.valueOf(name));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "unknown attribute operation: " + name);
            }
        },
        Operation::name);

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<AttributeModifier> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(AttributeModifier::id),
        OPERATION.fieldOf("operation").forGetter(AttributeModifier::operation),
        Codec.DOUBLE.fieldOf("amount").forGetter(AttributeModifier::amount)
    ).apply(instance, AttributeModifier::new));

    public AttributeModifier {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(operation, "operation");
    }
}
