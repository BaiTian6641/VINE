package dev.vineengine.vine.combat;

/**
 * The MODIFY phase's extension point (sub-10 §2, §5.20): skills, affinities, elemental
 * weaknesses, encounter rules — everything a game wants to change about a hit that is
 * not geometry and not the target's own hit zones.
 *
 * <p>Registered on the engine's extension surface, run in registration order, and
 * expected to be pure: a modifier that reads a clock or a random source makes the
 * pipeline's golden trace unreproducible, which is the one thing this design refuses to
 * trade away.
 *
 * <p>Return the same context it was given (the withers mutate it) — the return value
 * exists so a modifier can be written as one expression, never so a modifier can swap
 * the target of a hit.
 */
@FunctionalInterface
public interface CombatModifier {

    /** Adjusts {@code context}; return it, possibly after calling its withers. */
    HitContext modify(HitContext context);
}
