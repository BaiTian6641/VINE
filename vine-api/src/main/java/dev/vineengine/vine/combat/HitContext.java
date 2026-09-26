package dev.vineengine.vine.combat;

import java.util.Objects;

import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * One hit as it travels through the pipeline (sub-10 §2): what landed, on whom, on
 * which part, and for how much — mutated in place (withers return {@code this}) so a
 * {@link CombatModifier} can adjust damage, knockback or hitstop without boxing a
 * record per modifier.
 *
 * <p><b>Phases.</b> By the time a modifier sees it, SWEEP has already decided *who* was
 * hit and RESOLVE has already applied the part's hit-zone multiplier — so a modifier
 * adjusts a number that already means "this part, this damage type", which is the only
 * place where an affinity or a skill can act without second-guessing the geometry.
 *
 * <p><b>Determinism.</b> Modifiers run in registration order, and the damage they leave
 * behind is rounded half-up exactly once, at APPLY. A modifier that rounds, or that
 * reads a clock, breaks the golden trace — the pipeline is a pure function of its
 * inputs plus the registered order.
 */
public final class HitContext {

    private final AttackDescriptor attack;
    private final CombatActorRef attacker;
    private final VineEntityRef target;
    private final PartDescriptor part;
    private final PartState partState;
    private final double baseDamage;
    private final double hitzoneMultiplier;
    private double damage;
    private Vec3 knockback;
    private int hitstopTicks;
    private boolean cancelled;

    /** Builds the context RESOLVE hands to MODIFY. */
    public HitContext(AttackDescriptor attack, CombatActorRef attacker, VineEntityRef target, PartDescriptor part,
            PartState partState, double baseDamage, double hitzoneMultiplier) {
        this.attack = Objects.requireNonNull(attack, "attack");
        this.attacker = Objects.requireNonNull(attacker, "attacker");
        this.target = Objects.requireNonNull(target, "target");
        this.part = Objects.requireNonNull(part, "part");
        this.partState = Objects.requireNonNull(partState, "partState");
        this.baseDamage = baseDamage;
        this.hitzoneMultiplier = hitzoneMultiplier;
        this.damage = baseDamage * attack.motionValue() * hitzoneMultiplier;
        this.knockback = attack.knockback();
        this.hitstopTicks = attack.hitstopTicks();
    }

    public AttackDescriptor attack() {
        return attack;
    }

    public CombatActorRef attacker() {
        return attacker;
    }

    public VineEntityRef target() {
        return target;
    }

    /** The part the strike landed on. */
    public PartDescriptor part() {
        return part;
    }

    /** That part's state *before* this hit was applied. */
    public PartState partState() {
        return partState;
    }

    /** The weapon's damage before the attack's motion value. */
    public double baseDamage() {
        return baseDamage;
    }

    /** The part's multiplier for this attack's damage type. */
    public double hitzoneMultiplier() {
        return hitzoneMultiplier;
    }

    /** The damage after motion and hit zone; a modifier adjusts this. */
    public double damage() {
        return damage;
    }

    /** The damage type a target's hit zones answer to. */
    public VineId damageType() {
        return attack.damageType();
    }

    public HitContext damage(double newDamage) {
        this.damage = newDamage;
        return this;
    }

    /** Multiplies the damage — the shape most modifiers want. */
    public HitContext multiplyDamage(double factor) {
        this.damage *= factor;
        return this;
    }

    public Vec3 knockback() {
        return knockback;
    }

    public HitContext knockback(Vec3 newKnockback) {
        this.knockback = Objects.requireNonNull(newKnockback, "newKnockback");
        return this;
    }

    public int hitstopTicks() {
        return hitstopTicks;
    }

    public HitContext hitstopTicks(int newHitstopTicks) {
        if (newHitstopTicks < 0) {
            throw new IllegalArgumentException("hitstopTicks must be non-negative, got " + newHitstopTicks);
        }
        this.hitstopTicks = newHitstopTicks;
        return this;
    }

    /** Whether the hit is off: a modifier that vetoes it (immunity, a dodge buff). */
    public boolean cancelled() {
        return cancelled;
    }

    /** Vetoes the hit; the pipeline stops before APPLY and reports it as rejected. */
    public HitContext cancel() {
        this.cancelled = true;
        return this;
    }
}
