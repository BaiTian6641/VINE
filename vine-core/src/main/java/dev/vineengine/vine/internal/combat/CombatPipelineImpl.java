package dev.vineengine.vine.internal.combat;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.Quaternion;
import dev.vineengine.vine.combat.AttackDescriptor;
import dev.vineengine.vine.combat.CombatActorRef;
import dev.vineengine.vine.combat.CombatModifier;
import dev.vineengine.vine.combat.CombatResult;
import dev.vineengine.vine.combat.CombatState;
import dev.vineengine.vine.combat.HitContext;
import dev.vineengine.vine.combat.SweepShape;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.internal.CombatBackend;
import dev.vineengine.vine.internal.entity.PartRuntime;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.Vec3;

/**
 * The combat pipeline (sub-10 Stage C): SWEEP → RESOLVE → MODIFY → APPLY, in that order,
 * with no phase skipped and no phase reachable twice.
 *
 * <p><b>Why a fixed order matters more than a hook.</b> Every loader's event soup is what
 * this replaces. A stage that can be reordered is a stage whose result depends on
 * registration luck — so the phases are closed, the only consumer extension points are
 * {@link CombatModifier} (MODIFY) and the target's own part descriptors (RESOLVE), and
 * damage is rounded exactly once, at APPLY, half-up.
 *
 * <p><b>Server-authoritative.</b> Geometry is recomputed here from the attacker's own
 * transform and the target's engine-side part boxes; a client's claim about what it hit is
 * never an input. The reentrancy guard exists for the same reason in the other direction:
 * damage dealt through a loader's native path must not come back in as a second hit.
 *
 * <p><b>The worked example is the contract.</b> {@code base 40 × motion 1.6 × hitzone 1.3
 * × affinity 1.1 × element 1.2 → 109.824 → 110}. The headless fixture replays exactly
 * that, so a change in any phase's arithmetic fails a committed golden.
 */
public final class CombatPipelineImpl implements CombatBackend {

    private final CombatStateImpl state = new CombatStateImpl();
    /** Registration order is run order; a copy-on-write list keeps iteration allocation-free. */
    private final List<CombatModifier> modifiers = new CopyOnWriteArrayList<>();
    /** Set while the engine is applying damage, so a loader re-entry cannot start a second pass. */
    private static final AtomicBoolean APPLYING = new AtomicBoolean();

    /** Whether the engine is currently inside APPLY on this thread. */
    public static boolean applying() {
        return APPLYING.get();
    }

    @Override
    public CombatResult strike(CombatActorRef attacker, VineEntityRef target, VineId attackId, double baseDamage,
            Vec3 attackerPosition, float attackerYawDegrees) {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(attackerPosition, "attackerPosition");
        if (!(baseDamage > 0.0D)) {
            return CombatResult.refused(attackId, attacker, target, CombatResult.Phase.SWEEP,
                CombatResult.Rejection.INVALID_GEOMETRY);
        }
        AttackDescriptor attack = VineRegistries.<AttackDescriptor>get(VineContent.ATTACK_TYPE, attackId)
            .map(holder -> holder.value())
            .orElseThrow(() -> new IllegalArgumentException("no attack descriptor is registered under " + attackId
                + " — striking with undeclared content is a caller bug"));

        // ---- SWEEP: the attacker's own volume against the target's part colliders ----
        EntityDescriptor targetDescriptor = VineRegistries.<EntityDescriptor>get(VineContent.ENTITY_TYPE,
                target.entityId()).map(holder -> holder.value()).orElseThrow(() -> new IllegalArgumentException(
                "no entity descriptor is registered under " + target.entityId()));
        if (targetDescriptor.parts().isEmpty() || !VineParts.isHosted(target)) {
            // A single-collider target has no parts to route a hit to: the engine reports
            // that plainly instead of inventing a whole-body part the descriptor never
            // declared.
            return CombatResult.refused(attackId, attacker, target, CombatResult.Phase.SWEEP,
                CombatResult.Rejection.MISSED);
        }
        OrientedBox volume = sweepVolume(attack.sweep(), attackerPosition, attackerYawDegrees);
        PartDescriptor hitPart = null;
        OrientedBox hitBox = null;
        double nearest = Double.MAX_VALUE;
        for (PartDescriptor part : targetDescriptor.parts()) {
            Optional<OrientedBox> box = PartRuntime.boxAtHost(target, part.name());
            if (box.isEmpty()) {
                continue;
            }
            if (!volume.intersects(box.get())) {
                continue;
            }
            if (attack.sweep() instanceof SweepShape.Arc arc
                && !withinArc(arc, attackerPosition, attackerYawDegrees, box.get().center())) {
                continue;
            }
            // The nearest hit wins, so a weapon that clips two parts hits the one in front.
            Vec3 toBox = box.get().center().minus(attackerPosition);
            double distance = toBox.x() * toBox.x() + toBox.y() * toBox.y() + toBox.z() * toBox.z();
            if (distance < nearest) {
                nearest = distance;
                hitPart = part;
                hitBox = box.get();
            }
        }
        if (hitPart == null) {
            return CombatResult.miss(attackId, attacker);
        }

        // ---- RESOLVE: the part's own hit zone for this damage type ----
        PartState partState = VineParts.part(target, hitPart.name()).orElse(PartState.fresh(hitPart.name()));
        double hitzone = PartState.multiplierOf(hitPart, attack.damageType(), partState.broken());
        if (hitzone <= 0.0D) {
            // Explicit immunity is data, not a modifier: a part that answers 0 for a type
            // refuses that type without any game rule being consulted.
            return CombatResult.refused(attackId, attacker, target, CombatResult.Phase.RESOLVE,
                CombatResult.Rejection.MODIFIER_CANCELLED);
        }

        // ---- MODIFY: registered modifiers, in registration order ----
        HitContext context = new HitContext(attack, attacker, target, hitPart, partState, baseDamage, hitzone);
        for (CombatModifier modifier : modifiers) {
            CombatModifier current = Objects.requireNonNull(modifier, "registered modifier");
            HitContext returned = current.modify(context);
            if (returned != null && returned != context) {
                throw new IllegalStateException("a CombatModifier returned a different HitContext than it was"
                    + " given — a modifier adjusts a hit, it never replaces its target");
            }
            if (context.cancelled()) {
                return CombatResult.refused(attackId, attacker, target, CombatResult.Phase.MODIFY,
                    CombatResult.Rejection.MODIFIER_CANCELLED);
            }
        }

        // ---- APPLY: i-frames, then damage, then the target's own state ----
        CombatActorRef targetRef = new CombatActorRef.Actor(target);
        if (state.inIFrames(targetRef)) {
            return CombatResult.refused(attackId, attacker, target, CombatResult.Phase.APPLY,
                CombatResult.Rejection.I_FRAMES);
        }
        long rounded = Math.round(context.damage());
        Optional<dev.vineengine.vine.entity.VineParts.PartHit> applied;
        APPLYING.set(true);
        try {
            applied = PartRuntime.applyResolved(target, hitPart.name(), rounded);
        } finally {
            APPLYING.set(false);
        }
        if (applied.isEmpty()) {
            return CombatResult.refused(attackId, attacker, target, CombatResult.Phase.APPLY,
                CombatResult.Rejection.INVALID_GEOMETRY);
        }
        if (context.hitstopTicks() > 0) {
            state.applyHitstop(targetRef, context.hitstopTicks());
        }
        return new CombatResult(attackId, attacker, Optional.of(target), Optional.of(hitPart.name()),
            applied.get().appliedAmount(), applied.get().broke(), applied.get().flinched(),
            context.hitstopTicks(), context.knockback(), CombatResult.Phase.APPLY, null);
    }

    @Override
    public CombatState state() {
        return state;
    }

    @Override
    public void addModifier(CombatModifier modifier) {
        modifiers.add(Objects.requireNonNull(modifier, "modifier"));
    }

    @Override
    public int modifierCount() {
        return modifiers.size();
    }

    /** The state object itself, for the per-tick decay the cell drives. */
    public CombatStateImpl stateImpl() {
        return state;
    }

    /**
     * The attacker's sweep volume in world space: the descriptor's shape, offset in front
     * of the attacker along its facing. Yaw follows the pose evaluator's convention
     * ({@code actorPosition + Ry(−yaw) · p}), so a sweep and a part box can never disagree
     * about which way the fight is pointing.
     */
    static OrientedBox sweepVolume(SweepShape shape, Vec3 attackerPosition, float attackerYawDegrees) {
        Quaternion yaw = Quaternion.fromEulerDegrees(Vec3.of(0.0D, -attackerYawDegrees, 0.0D));
        return new OrientedBox(attackerPosition.plus(yaw.rotate(shape.offset())), shape.halfExtents(), yaw);
    }

    /**
     * Whether {@code point} lies inside an arc's horizontal span. The test is done in the
     * attacker's own model space — the delta is rotated back by the actor's yaw and the
     * span measured against model-space forward — so the arc needs no opinion about which
     * way a cell's yaw points. That is deliberate: a sign convention guessed here would be
     * wrong on one cell and right on the other, and nothing would catch it.
     */
    static boolean withinArc(SweepShape.Arc arc, Vec3 attackerPosition, float attackerYawDegrees, Vec3 point) {
        Vec3 delta = Vec3.of(point.x() - attackerPosition.x(), 0.0D, point.z() - attackerPosition.z());
        double horizontal = Math.hypot(delta.x(), delta.z());
        if (horizontal == 0.0D) {
            return true;
        }
        Vec3 local = Quaternion.fromEulerDegrees(Vec3.of(0.0D, attackerYawDegrees, 0.0D)).rotate(delta);
        // Model-space forward is −Z: every descriptor in the plan puts a weapon's offset
        // and a beast's head at negative z.
        double cosine = -local.z() / horizontal;
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0D, Math.min(1.0D, cosine))));
        return angle <= arc.angleDegrees() * 0.5D;
    }
}
