package dev.vineengine.vine.entity;

import java.util.Objects;

import dev.vineengine.vine.registry.VineId;

/**
 * One part's live state (sub-08 Stage D): how much damage it has accumulated, whether
 * it is broken, and when it last flinched. A value, read out of the actor's own stored
 * tree — the same tree the save path writes, so a wound survives a reload without any
 * separate mechanism.
 *
 * <p><b>Why the state is per part and not per actor:</b> a Monster-Hunter-shaped fight
 * is about *where* the damage lands. A tail that has taken 120 damage and a head that
 * has taken 120 are in completely different situations, and the engine has to be able
 * to answer "is the tail broken?" without asking a cell.
 *
 * @param name    the part's descriptor name
 * @param wound   total damage accumulated on this part
 * @param broken  whether the part has crossed its declared break threshold
 * @param lastFlinchTick the tick of the most recent flinch, or {@code Long.MIN_VALUE}
 *                       when the part has never flinched
 */
public record PartState(String name, double wound, boolean broken, long lastFlinchTick) {

    public PartState {
        Objects.requireNonNull(name, "name");
        if (wound < 0.0D) {
            throw new IllegalArgumentException("PartState " + name + ": wound must be non-negative, got " + wound);
        }
    }

    /** A part that has taken nothing yet. */
    public static PartState fresh(String name) {
        return new PartState(name, 0.0D, false, Long.MIN_VALUE);
    }

    /** Whether this part has ever flinched. */
    public boolean hasFlinched() {
        return lastFlinchTick != Long.MIN_VALUE;
    }

    /** This state with {@code amount} more damage on it. */
    public PartState woundedBy(double amount) {
        return new PartState(name, wound + amount, broken, lastFlinchTick);
    }

    /** This state marked broken. */
    public PartState broken(boolean nowBroken) {
        return new PartState(name, wound, nowBroken, lastFlinchTick);
    }

    /** This state with a flinch stamped at {@code tick}. */
    public PartState flinchedAt(long tick) {
        return new PartState(name, wound, broken, tick);
    }

    /**
     * The multiplier this part applies to {@code damageType} while {@code intact},
     * against its descriptor — absent means neutral (1.0), and the descriptor's own
     * zero means immunity.
     */
    public static double multiplierOf(PartDescriptor descriptor, VineId damageType, boolean broken) {
        Float declared = descriptor.damageMultipliers().get(damageType);
        double base = declared == null ? 1.0D : declared;
        return broken ? base * BROKEN_FACTOR : base;
    }

    /**
     * How a broken part scales its declared multipliers. Locked at 1.5 because a broken
     * part is a *soft spot*, not a shield: the plan says breaking "changes its
     * multipliers" without fixing the number, and a defensible default beats an
     * unspecified one. A future stage may make it per-descriptor; until then every cell
     * reads this same constant, so a break means the same thing everywhere.
     */
    public static final double BROKEN_FACTOR = 1.5D;
}
