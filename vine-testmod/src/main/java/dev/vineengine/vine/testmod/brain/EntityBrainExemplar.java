package dev.vineengine.vine.testmod.brain;

import dev.vineengine.vine.brain.Action;
import dev.vineengine.vine.brain.BlackboardKey;
import dev.vineengine.vine.brain.NodeStatus;
import dev.vineengine.vine.brain.Primitives;
import dev.vineengine.vine.brain.VineBrain;
import dev.vineengine.vine.brain.VineBrains;
import dev.vineengine.vine.entity.VineEntities;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorlds;

/**
 * The sub-08 Stage C exemplar: a spawned entity walked to a target by the engine's
 * own behaviour runtime, on a live cell.
 *
 * <p>What this proves that Stage B could not: the brain is ticked by the <em>cell</em>
 * (the entity calls back into {@code EntityRuntime} once per server tick), the actor
 * reaches the world only through the cell's own {@link Primitives} implementation
 * (native navigation and raycasts), and the engine still owns the decision — the
 * behaviour below is the same kind of node a consumer writes, not a cell special
 * case. The walk target sits behind a wall in the scenario, so a straight line is not
 * enough: the cell's pathing has to find a way around.
 *
 * <p>The acceptance is behavioural, never a path shape or an exact tick: the scenario
 * asserts that the beast arrived, and within a tick budget large enough that neither
 * cell's navigation quality is being graded.
 */
public final class EntityBrainExemplar {

    /** Where the beast starts, in the scenario's platform coordinates. */
    public static final Vec3 START = Vec3.of(2.5D, 300.0D, 0.5D);

    /** How often the behaviour prints its progress, in ticks. */
    private static final int REPORT_EVERY = 20;

    /** How close counts as arrived. */
    private static final double ARRIVAL_RADIUS = 1.5D;

    /** Blackboard: the target this beast is walking to. */
    private static final BlackboardKey<String> TARGET = BlackboardKey.stringKey("walk.target");

    /** Blackboard: the tick the walk began, so the report can quote a duration. */
    private static final BlackboardKey<Long> STARTED = BlackboardKey.longKey("walk.started");

    private EntityBrainExemplar() {
    }

    /**
     * Spawns one beast at {@code start}, attaches a walk behaviour bound for
     * {@code target}, and prints what it did — the scenario asserts the arrival line.
     *
     * @return {@code false} when the cell refused the spawn (reported, never guessed)
     */
    public static boolean spawnAndWalk(Vec3 start, Vec3 target) {
        var spawned = VineEntities.spawn(VineId.of("vine_test", "testbeast"), VineWorlds.overworld(), start);
        if (spawned.isEmpty()) {
            System.out.println("tck: beast spawn refused at=" + start.asString());
            return false;
        }
        VineEntityRef ref = spawned.get();
        System.out.println("tck: beast spawned ref=" + ref + " at=" + start.asString());

        attachBrain(ref, target);
        return true;
    }

    /**
     * Attaches this exemplar's walking brain to {@code ref}. Split out because the brain is not
     * only for actors this exemplar spawned: the multipart exemplar attaches it to its own hosted
     * beast so a part's flinch can interrupt a node (sub-08 Stage D's last acceptance clause).
     */
    public static void attachBrain(VineEntityRef ref, Vec3 target) {
        VineBrain brain = VineBrains.of(ref.entityId(),
            dev.vineengine.vine.data.VineData.create(BrainExemplar.BRAIN_SCHEMA_ID));
        brain.blackboard().set(TARGET, target.asString());
        brain.set(brain.stateMachine()
            .initial("walk")
            .state("walk", walkTo(target))
            .build());
        VineEntities.attach(ref, brain);
    }

    /**
     * One action, three outcomes: ask for a path once, step along it, and report
     * arrival when the actor is close enough. Progress is printed on a fixed cadence so
     * a human reading a failure can see whether the beast moved at all.
     */
    private static dev.vineengine.vine.brain.Node walkTo(Vec3 target) {
        return Action.of("walk-to-target", ctx -> {
            // A part crossing its flinch threshold interrupts this node: the signal is consumed
            // once, so the node that reads it first is the one that reacts, and the walk reports
            // the interrupt rather than continuing to a target it can no longer be sure about.
            if (ctx.primitives().consumeFlinch()) {
                System.out.println("tck: beast interrupted by flinch at="
                    + ctx.primitives().position().asString() + " tick=" + ctx.tick());
                return NodeStatus.FAILURE;
            }
            if (ctx.blackboard().get(STARTED, 0L) == 0L) {
                ctx.blackboard().set(STARTED, ctx.tick());
            }
            Vec3 here = ctx.primitives().position();
            double distance = Math.sqrt(Math.pow(here.x() - target.x(), 2.0D)
                + Math.pow(here.y() - target.y(), 2.0D) + Math.pow(here.z() - target.z(), 2.0D));
            if (distance <= ARRIVAL_RADIUS) {
                long ticks = ctx.tick() - ctx.blackboard().get(STARTED, ctx.tick());
                System.out.println("tck: beast arrived at=" + here.asString() + " ticks=" + ticks);
                return NodeStatus.SUCCESS;
            }
            if (ctx.tick() % REPORT_EVERY == 0) {
                System.out.println("tck: beast progress at=" + here.asString() + " distance="
                    + String.format(java.util.Locale.ROOT, "%.2f", distance));
            }
            if (ctx.primitives().requestPath(target) == Primitives.PathOutcome.UNREACHABLE) {
                System.out.println("tck: beast cannot reach target=" + target.asString());
                return NodeStatus.FAILURE;
            }
            return switch (ctx.primitives().stepPath()) {
                case ARRIVED, ADVANCED -> NodeStatus.RUNNING;
                case BLOCKED -> {
                    System.out.println("tck: beast blocked at=" + here.asString());
                    yield NodeStatus.FAILURE;
                }
            };
        });
    }
}
