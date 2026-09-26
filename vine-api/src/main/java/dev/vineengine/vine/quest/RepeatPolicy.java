package dev.vineengine.vine.quest;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Whether a quest may be done again, and how soon (sub-15 §2).
 *
 * <p>Encoded as {@code {"kind": "once"}} or {@code {"kind": "repeatable", "cooldownTicks": 24000}}
 * — a plain tick count rather than a duration string, because the engine's clock is the
 * server tick and a second unit would be a conversion nobody asked for.
 */
public record RepeatPolicy(Kind kind, int cooldownTicks) {

    /** How a quest repeats. */
    public enum Kind {

        /** Finished once, for good. */
        ONCE,

        /** May be started again after its cooldown. */
        REPEATABLE
    }

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<RepeatPolicy> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.optionalFieldOf("kind", "once").forGetter(policy -> policy.kind().name().toLowerCase(
            java.util.Locale.ROOT)),
        Codec.INT.optionalFieldOf("cooldownTicks", 0).forGetter(RepeatPolicy::cooldownTicks)
    ).apply(instance, (kind, cooldown) -> new RepeatPolicy(Kind.valueOf(kind.toUpperCase(java.util.Locale.ROOT)),
        cooldown)));

    /** A quest that happens once. */
    public static final RepeatPolicy ONCE = new RepeatPolicy(Kind.ONCE, 0);

    public RepeatPolicy {
        Objects.requireNonNull(kind, "kind");
        if (cooldownTicks < 0) {
            throw new IllegalArgumentException("RepeatPolicy: cooldownTicks must be non-negative, got "
                + cooldownTicks);
        }
        if (kind == Kind.ONCE && cooldownTicks != 0) {
            throw new IllegalArgumentException("RepeatPolicy: a once-only quest cannot have a cooldown");
        }
    }
}
