package dev.vineengine.vine.combat;

import java.util.Objects;

import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.internal.CombatBackend;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * Static entry point for combat (sub-10 §2). Mirror of the other facades: a consumer
 * declares actions and attacks as data, registers the modifiers its game needs, and calls
 * {@link #strike} — the pipeline's phases and the server's authority are the engine's.
 *
 * <p><b>Server-authoritative, no exceptions.</b> {@link #strike} is what a driver calls
 * when a loader reports an attack, and what a scenario calls in a test. A client never
 * sends a hit result; the server computes the sweep itself, from the attacker's own
 * position and facing, and the only thing that travels back is presentation.
 */
public final class VineCombat {

    private VineCombat() {
    }

    /**
     * Runs one strike: SWEEP → RESOLVE → MODIFY → APPLY, against {@code target}.
     *
     * @param attacker the actor or player swinging (see {@link CombatActorRef})
     * @param attackerPosition where the attacker stands (the engine never keeps a second
     *                         copy of a cell's transform)
     * @param attackerYawDegrees the attacker's facing, in the cell's own convention
     * @param baseDamage       the weapon's damage before the attack's motion value
     */
    public static CombatResult strike(CombatActorRef attacker, VineEntityRef target, VineId attackId,
            double baseDamage, Vec3 attackerPosition, float attackerYawDegrees) {
        return backend().strike(Objects.requireNonNull(attacker, "attacker"),
            Objects.requireNonNull(target, "target"), Objects.requireNonNull(attackId, "attackId"), baseDamage,
            Objects.requireNonNull(attackerPosition, "attackerPosition"), attackerYawDegrees);
    }

    /** The engine's combat state: i-frames and hitstop, server-side. */
    public static CombatState state() {
        return backend().state();
    }

    /**
     * Registers a MODIFY-phase modifier. Registration order is the run order, so a game's
     * rules compose deterministically; a modifier registered after the registry freezes
     * is refused rather than silently ignored.
     */
    public static void addModifier(CombatModifier modifier) {
        backend().addModifier(Objects.requireNonNull(modifier, "modifier"));
    }

    /** How many modifiers are registered — a probe for tests and the boot log. */
    public static int modifierCount() {
        return backend().modifierCount();
    }

    private static CombatBackend backend() {
        if (EngineAccess.get() instanceof CombatBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide combat (headless runtime, or vine-core is not the engine)");
    }
}
