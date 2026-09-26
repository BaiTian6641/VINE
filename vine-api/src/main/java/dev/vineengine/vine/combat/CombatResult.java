package dev.vineengine.vine.combat;

import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.registry.VineId;

/**
 * What a strike did (sub-10 §2): the damage that actually landed, whether a part broke
 * or flinched, and the presentation facts a client needs — or the reason it did not
 * land at all, which is always named rather than implied.
 *
 * <p>A strike that hits nothing is not an error: it is {@link #miss()} with the phase
 * that ended it, so a consumer can distinguish "out of reach" from "in i-frames" from
 * "the modifier cancelled it" without parsing a log.
 */
public record CombatResult(VineId attackId, CombatActorRef attacker, Optional<VineEntityRef> target,
        Optional<String> part, double damage, boolean broke, boolean flinched, int hitstopTicks,
        dev.vineengine.vine.world.Vec3 knockback, Phase endedAt, Rejection rejection) {

    /** Which phase produced this result. */
    public enum Phase {

        /** No part collider was inside the sweep volume. */
        SWEEP,

        /** A part was found and its hit-zone multiplier resolved. */
        RESOLVE,

        /** A modifier vetoed the hit, or i-frames refused it. */
        MODIFY,

        /** Damage was applied. */
        APPLY
    }

    /** Why a hit did not land, when it did not. */
    public enum Rejection {

        /** Nothing was inside the sweep. */
        MISSED,

        /** The target is in invulnerability frames. */
        I_FRAMES,

        /** A modifier cancelled the hit. */
        MODIFIER_CANCELLED,

        /** The strike's geometry was unusable (an absurd reach, a missing transform). */
        INVALID_GEOMETRY
    }

    public CombatResult {
        Objects.requireNonNull(attackId, "attackId");
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(knockback, "knockback");
        Objects.requireNonNull(endedAt, "endedAt");
    }

    /** Whether the strike landed. */
    public boolean landed() {
        return rejection == null;
    }

    /** A strike that ended before APPLY, with the reason. */
    public static CombatResult refused(VineId attackId, CombatActorRef attacker, VineEntityRef target,
            Phase endedAt, Rejection rejection) {
        return new CombatResult(attackId, attacker, Optional.of(target), Optional.empty(), 0.0D, false, false, 0,
            dev.vineengine.vine.world.Vec3.ZERO, endedAt, rejection);
    }

    /** A strike that found nothing at all. */
    public static CombatResult miss(VineId attackId, CombatActorRef attacker) {
        return new CombatResult(attackId, attacker, Optional.empty(), Optional.empty(), 0.0D, false, false, 0,
            dev.vineengine.vine.world.Vec3.ZERO, Phase.SWEEP, Rejection.MISSED);
    }
}
