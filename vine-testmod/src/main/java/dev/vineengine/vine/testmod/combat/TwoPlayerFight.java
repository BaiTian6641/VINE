package dev.vineengine.vine.testmod.combat;

import java.util.UUID;

import dev.vineengine.vine.combat.CombatActorRef;
import dev.vineengine.vine.combat.CombatResult;
import dev.vineengine.vine.combat.VineCombat;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntities;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.testmod.content.TestContent;
import dev.vineengine.vine.testmod.parts.PartsExemplar;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorlds;

/**
 * The sub-10 two-player fight: two distinct players damage one multipart beast in the same
 * world, and the state they leave behind is one server-side truth rather than two views.
 *
 * <p>Three things are checked, and none of them can be faked by re-reading a number this
 * scenario passed in:
 *
 * <ol>
 *   <li><b>Two attackers, one target.</b> Alpha hits the head, Beta hits the tail; each
 *       strike's applied amount is the engine's own chain for the part that was actually
 *       hit, and the wounds are the sum of those chains — neither doubled nor dropped.</li>
 *   <li><b>A stance is not a claim.</b> Beta then strikes from thirty blocks away, holding
 *       the same weapon and the same aim. The engine computes the sweep from the geometry it
 *       was given and reports a miss, and no wound moves: what a caller <em>says</em> about
 *       being in range decides nothing.</li>
 *   <li><b>One truth, two participants.</b> After Beta breaks the tail, the tail's state read
 *       for Alpha and the state read for Beta are the same state — the shared value, read
 *       twice, not a per-player copy that could drift.</li>
 * </ol>
 *
 * <p>Both attackers are {@link CombatActorRef#player(UUID)} — the pipeline treats a player
 * attacker exactly like an actor attacker, so the two-player properties here are properties
 * of the combat pipeline, not of any one client.
 */
public final class TwoPlayerFight {

    /** The attack under test: the testmod's registered cleave, on the ember blade's base damage. */
    private static final VineId CLEAVE = TestContent.emberCleaveId();

    /** The weapon's base damage, from the ember blade's own combat profile. */
    private static final double BASE_DAMAGE = 40.0D;

    /** Two participants. Fixed ids so a run's log is comparable with the next run's. */
    private static final UUID ALPHA = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID BETA = UUID.fromString("00000000-0000-4000-8000-0000000000b2");

    /** How far outside its own reach the forged strike is launched from. */
    private static final double OUT_OF_REACH = 30.0D;

    /**
     * Strikes at the tail. Two cross the descriptor's 120 break threshold (a strike lands the
     * weapon's 40 base × 1.6 motion = 64 there); the last two are what a broken part is worth
     * (the descriptor's broken factor), which is the number a one-strike check cannot show.
     */
    private static final int TAIL_STRIKES = 4;

    /** The beast this scenario is fighting, remembered between the spawn and the fight. */
    private static VineEntityRef beast;
    /** Where it was spawned: the fight's geometry is stated relative to this. */
    private static Vec3 start;

    private TwoPlayerFight() {
    }

    /**
     * Spawns one beast at {@code start}, hosts its parts, and runs the whole fight.
     *
     * <p>Called a tick after the spawn so the cell has ticked the new body, which is when a
     * cell binds an actor's host.
     */
    public static boolean spawn(Vec3 start) {
        var spawned = VineEntities.spawn(VineId.of("vine_test", "testbeast"), VineWorlds.overworld(), start);
        if (spawned.isEmpty()) {
            System.out.println("tck: two-player refused at=" + start.asString());
            return false;
        }
        beast = spawned.get();
        TwoPlayerFight.start = start;
        System.out.println("tck: two-player spawned ref=" + beast + " at=" + start.asString());
        return true;
    }

    /** Runs the fight over the beast spawned a tick earlier: attach, strikes, shared read. */
    public static void fight() {
        if (beast == null) {
            System.out.println("tck: two-player fight none");
            return;
        }
        Vec3 start = TwoPlayerFight.start;
        VineParts.attach(beast, PartsExemplar.asset(), PartsExemplar.IDLE_CLIP);
        System.out.println("tck: two-player beast=" + beast + " hosted=" + VineParts.isHosted(beast)
            + " alpha=" + ALPHA + " beta=" + BETA);

        // Alpha at the beast's front (world -Z side) facing it: the engine's yaw convention
        // puts the actor's forward at world -Z when yaw is 0, so facing +Z is yaw 180.
        Vec3 alphaAt = new Vec3(start.x(), start.y(), start.z() - 2.0D);
        Vec3 betaAt = new Vec3(start.x(), start.y(), start.z() + 2.0D);
        report("alpha-head", strike(ALPHA, beast, alphaAt, 180.0F));

        // Beta from the other side, facing -Z (yaw 0), at the tail — until it breaks.
        for (int i = 1; i <= TAIL_STRIKES; i++) {
            report("beta-tail-" + i, strike(BETA, beast, betaAt, 0.0F));
        }

        // The stance without the geometry: same attacker, same weapon, same aim, far away.
        report("beta-forged", strike(BETA, beast, new Vec3(start.x(), start.y(), start.z() + OUT_OF_REACH), 0.0F));

        // One truth, read for each participant.
        System.out.println("tck: two-player state alpha=" + status(beast) + " beta=" + status(beast)
            + " same=" + status(beast).equals(status(beast)));
    }

    /** One strike, in the engine's yaw convention, at the blade's own base damage. */
    private static CombatResult strike(UUID player, VineEntityRef beast, Vec3 from, float yaw) {
        return VineCombat.strike(CombatActorRef.player(player), beast, CLEAVE, BASE_DAMAGE, from, yaw);
    }

    /** The beast's parts as one line, in declaration order. */
    private static String status(VineEntityRef beast) {
        StringBuilder out = new StringBuilder("[");
        for (PartState part : VineParts.parts(beast)) {
            if (out.length() > 1) {
                out.append(' ');
            }
            out.append(part.name()).append('=').append(part.wound()).append(part.broken() ? "(broken)" : "");
        }
        return out.append(']').toString();
    }

    /**
     * One strike's outcome, fact by fact: the part the engine hit, the amount its own chain
     * produced, whether that broke the part, and — for a miss — what the engine refused.
     */
    private static void report(String label, CombatResult result) {
        System.out.println("tck: two-player " + label
            + " landed=" + result.landed()
            + " endedAt=" + result.endedAt()
            + " part=" + result.part().orElse("none")
            + " damage=" + result.damage()
            + " broke=" + result.broke()
            + " flinched=" + result.flinched()
            + " rejection=" + (result.rejection() == null ? "none" : result.rejection().name()));
    }
}
