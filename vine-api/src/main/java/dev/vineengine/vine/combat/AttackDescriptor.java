package dev.vineengine.vine.combat;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * One strike as data (sub-10 Stage A). The motion value is the multiplier a game
 * designer tunes against the weapon's base damage — the Monster-Hunter-shaped knob —
 * and the damage type is what the target's per-part hit zones answer to.
 *
 * <p><b>Where the numbers meet:</b> the pipeline computes
 * {@code base × motionValue × hitzone(part, damageType) × modifiers}, never reading a
 * part's raw damage, so "a greatsword to the head" and "a dagger to the tail" are two
 * different fights with one code path.
 *
 * @param id          the attack's engine id
 * @param motionValue multiplier applied to the weapon's base damage
 * @param damageType  the damage type a target's hit zones answer to
 * @param element     the element a target's weaknesses answer to, or {@code null} for raw
 * @param sweep       the volume the strike looks for targets in
 * @param knockback   the default knockback this strike applies (a modifier may override it)
 * @param hitstopTicks the default freeze this strike imposes on its target
 */
public record AttackDescriptor(VineId id, double motionValue, VineId damageType, VineId element, SweepShape sweep,
        Vec3 knockback, int hitstopTicks) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<AttackDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(AttackDescriptor::id),
        Codec.DOUBLE.fieldOf("motionValue").forGetter(AttackDescriptor::motionValue),
        VineId.CODEC.fieldOf("damageType").forGetter(AttackDescriptor::damageType),
        VineId.CODEC.optionalFieldOf("element").forGetter(descriptor ->
            java.util.Optional.ofNullable(descriptor.element())),
        SweepShape.CODEC.fieldOf("sweep").forGetter(AttackDescriptor::sweep),
        Vec3.CODEC.optionalFieldOf("knockback", Vec3.ZERO).forGetter(AttackDescriptor::knockback),
        Codec.INT.optionalFieldOf("hitstopTicks", 0).forGetter(AttackDescriptor::hitstopTicks)
    ).apply(instance, (id, motion, damageType, element, sweep, knockback, hitstop) -> new AttackDescriptor(id,
        motion, damageType, element.orElse(null), sweep, knockback, hitstop)));

    public AttackDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(damageType, "damageType");
        Objects.requireNonNull(sweep, "sweep");
        Objects.requireNonNull(knockback, "knockback");
        if (!(motionValue > 0.0D)) {
            throw new IllegalArgumentException("AttackDescriptor " + id + ": motionValue must be positive, got "
                + motionValue);
        }
        if (hitstopTicks < 0) {
            throw new IllegalArgumentException("AttackDescriptor " + id + ": hitstopTicks must be non-negative, got "
                + hitstopTicks);
        }
    }

    /** A neutral strike: 1.0 motion, a box of the given reach, no knockback, no hitstop. */
    public static AttackDescriptor of(VineId id, double motionValue, VineId damageType, SweepShape sweep) {
        return new AttackDescriptor(id, motionValue, damageType, null, sweep, Vec3.ZERO, 0);
    }
}
