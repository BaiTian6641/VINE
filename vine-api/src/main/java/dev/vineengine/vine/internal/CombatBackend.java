package dev.vineengine.vine.internal;

import dev.vineengine.vine.combat.CombatActorRef;
import dev.vineengine.vine.combat.CombatModifier;
import dev.vineengine.vine.combat.CombatResult;
import dev.vineengine.vine.combat.CombatState;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * Internal bridge from {@code VineCombat} (vine-api) to vine-core's pipeline. NOT public
 * API — implemented once by vine-core's engine object; never implemented or referenced by
 * consumers.
 *
 * <p>Resolved through {@link EngineAccess}, so the engine's exactly-one-provider rule,
 * boot ordering and cached boot failure apply unchanged (same pattern as the other
 * backends).
 */
public interface CombatBackend {

    /** See {@code VineCombat#strike}. */
    CombatResult strike(CombatActorRef attacker, VineEntityRef target, VineId attackId, double baseDamage,
            Vec3 attackerPosition, float attackerYawDegrees);

    /** See {@code VineCombat#state}. */
    CombatState state();

    /** See {@code VineCombat#addModifier}. */
    void addModifier(CombatModifier modifier);

    /** See {@code VineCombat#modifierCount}. */
    int modifierCount();
}
