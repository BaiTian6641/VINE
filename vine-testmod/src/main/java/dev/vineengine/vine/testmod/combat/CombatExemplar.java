package dev.vineengine.vine.testmod.combat;

import java.util.Optional;

import dev.vineengine.vine.combat.CombatActorRef;
import dev.vineengine.vine.combat.CombatResult;
import dev.vineengine.vine.combat.VineCombat;
import dev.vineengine.vine.entity.VineEntities;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.testmod.content.TestContent;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorlds;

/**
 * The sub-10 Stage C exemplar: the plan's own worked example, run on a live cell.
 *
 * <pre>
 *   base 40 × motion 1.6 (attack)        → 64.0
 *   × hitzone 1.3 (head part, sub-08)    → 83.2
 *   × affinity +10% (modifier)           → 91.52
 *   × element weakness 1.2 (modifier)    → 109.824
 *   APPLY: i-frames? no → round half-up  → 110 damage
 *          knockback override (2.5, up 0.4); hitstop 3 ticks
 * </pre>
 *
 * <p>Every number in that chain comes from a different part of the engine — the weapon's
 * base damage from the item descriptor, the motion value and hit zone from descriptors, the
 * last two factors from two registered {@code CombatModifier}s — which is the point: a
 * scenario that only reads the engine's own output cannot fake a chain it did not run.
 */
public final class CombatExemplar {

    /** The Java-registered attack (sub-10 Stage A). */
    private static final VineId CLEAVE = TestContent.emberCleaveId();

    /** The weapon's base damage, from the ember blade's own combat profile. */
    private static final double BASE_DAMAGE = 40.0D;

    /** The affinity modifier's factor: the worked example's "+10%". */
    private static final double AFFINITY = 1.1D;

    /** The element modifier's factor: the worked example's "element weakness 1.2". */
    private static final double ELEMENT_WEAKNESS = 1.2D;

    private static VineEntityRef target;
    private static VineEntityRef attacker;
    private static Vec3 attackerStart;
    private static boolean modifiersRegistered;

    private CombatExemplar() {
    }

    /** Spawns the target and the attacker; the strike itself waits for the next tick. */
    public static boolean spawn(Vec3 targetStart, Vec3 attackerStart) {
        Optional<VineEntityRef> spawnedTarget = VineEntities.spawn(VineId.of("vine_test", "testbeast"),
            VineWorlds.overworld(), targetStart);
        Optional<VineEntityRef> spawnedAttacker = VineEntities.spawn(VineId.of("vine_test", "testbeast"),
            VineWorlds.overworld(), attackerStart);
        if (spawnedTarget.isEmpty() || spawnedAttacker.isEmpty()) {
            System.out.println("tck: combat spawn refused targetAt=" + targetStart.asString()
                + " attackerAt=" + attackerStart.asString());
            return false;
        }
        target = spawnedTarget.get();
        attacker = spawnedAttacker.get();
        CombatExemplar.attackerStart = attackerStart;
        System.out.println("tck: combat spawned target=" + target + " attacker=" + attacker
            + " targetAt=" + targetStart.asString() + " attackerAt=" + attackerStart.asString());
        return true;
    }

    /** Hosts the target's parts, registers the two modifiers, and runs the strike. */
    public static void strike(Vec3 targetStart) {
        if (target == null || attacker == null) {
            System.out.println("tck: combat strike none");
            return;
        }
        VineParts.attach(target, dev.vineengine.vine.testmod.parts.PartsExemplar.asset(), "animation.vine_test"
            + ".wyvern_stub.idle");
        registerModifiers();
        System.out.println("tck: combat modifiers=" + VineCombat.modifierCount());

        double dx = targetStart.x() - attackerStart.x();
        double dz = targetStart.z() - attackerStart.z();
        float yaw = (float) Math.toDegrees(Math.atan2(dx, -dz));
        System.out.println("tck: combat geometry attackerAt=" + attackerStart.asString()
            + " yaw=" + yaw
            + " headBox=" + VineParts.box(target, "head", targetStart, 0.0F)
                .map(box -> box.center().asString()).orElse("none"));
        CombatResult result = VineCombat.strike(CombatActorRef.actor(attacker), target, CLEAVE, BASE_DAMAGE,
            attackerStart, yaw);
        report("hit-1", result);

        // A second strike lands while the target is inside engine i-frames: the pipeline
        // refuses it at APPLY, naming the reason, and the wound does not move.
        VineCombat.state().grantIFrames(CombatActorRef.actor(target), 10);
        report("hit-2-iframed", VineCombat.strike(CombatActorRef.actor(attacker), target, CLEAVE, BASE_DAMAGE,
            attackerStart, yaw));
    }

    /** Reports the target's state a few ticks later: hitstop decayed, wound unchanged. */
    public static void after() {
        if (target == null) {
            System.out.println("tck: combat after none");
            return;
        }
        // Two lines, because they answer two different questions: hitstop decayed on its own
        // (deterministic), and the wound did not move while the target was in i-frames (the
        // i-frame boolean itself depends on how many ticks the runner spent issuing commands,
        // so it is printed for the log but asserted through the refusal it produced).
        System.out.println("tck: combat after hitstop="
            + VineCombat.state().hitstopTicks(CombatActorRef.actor(target))
            + " inIFrames=" + VineCombat.state().inIFrames(CombatActorRef.actor(target)));
        System.out.println("tck: combat wounds head="
            + VineParts.part(target, "head").map(state -> Double.toString(state.wound())).orElse("none")
            + " tail=" + VineParts.part(target, "tail").map(state -> Double.toString(state.wound()))
                .orElse("none"));
    }

    /** One result, printed fact by fact so the scenario asserts what the engine produced. */
    private static void report(String label, CombatResult result) {
        System.out.println("tck: combat " + label
            + " landed=" + result.landed()
            + " damage=" + result.damage()
            + " part=" + result.part().orElse("none")
            + " broke=" + result.broke()
            + " flinched=" + result.flinched()
            + " hitstop=" + result.hitstopTicks()
            + " knockback=" + result.knockback().asString()
            + " endedAt=" + result.endedAt()
            + " rejection=" + (result.rejection() == null ? "none" : result.rejection().name()));
    }

    /** The example's two game rules, registered as ordinary modifiers, in order. */
    private static void registerModifiers() {
        if (modifiersRegistered) {
            return;
        }
        modifiersRegistered = true;
        VineCombat.addModifier(context -> context.multiplyDamage(AFFINITY));
        VineCombat.addModifier(context -> context.multiplyDamage(ELEMENT_WEAKNESS).knockback(
            dev.vineengine.vine.world.Vec3.of(2.5D, 0.4D, 0.0D)));
    }
}
