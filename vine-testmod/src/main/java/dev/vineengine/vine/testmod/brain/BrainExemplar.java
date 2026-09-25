package dev.vineengine.vine.testmod.brain;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.brain.Action;
import dev.vineengine.vine.brain.BlackboardKey;
import dev.vineengine.vine.brain.Node;
import dev.vineengine.vine.brain.NodeStatus;
import dev.vineengine.vine.brain.Primitives;
import dev.vineengine.vine.brain.TapePrimitives;
import dev.vineengine.vine.brain.VineBrain;
import dev.vineengine.vine.brain.VineBrains;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The sub-08 Stage B exemplar: one actor's hunt loop (wander → chase), evaluated
 * headless against a recorded primitive tape, then replayed from that same tape —
 * the determinism claim the stage's acceptance rests on.
 *
 * <p>The tree is ordinary consumer code: it uses only the published brain API, reaches
 * the world only through {@link Primitives}, and keeps everything it remembers in the
 * blackboard. Two runs must agree on three things — per-tick statuses, the memory they
 * end with, and the exact sequence of primitive calls — and the exemplar prints all
 * three: the call digest and the memory digest are the golden values a scenario pins,
 * and the status trace is what a human reads when they differ.
 */
public final class BrainExemplar {

    /** The actor the exemplar drives. */
    public static final VineId ACTOR = VineId.of("vine_test", "hunter");

    /** The blackboard schema: the memory tree's identity, exactly like any other holder. */
    public static final VineId BRAIN_SCHEMA_ID = VineId.of("vine_test", "brain");

    /** Placeholder payload codec — the engine's blob codec does the real work. */
    private static final Codec<VoxelData> CODEC = Codec.unit(null);

    /** Where the wander loop patrols to. */
    private static final Vec3 PATROL = Vec3.of(8.0D, 64.0D, 0.0D);

    /** Where the actor last saw its quarry. */
    private static final Vec3 QUARRY = Vec3.of(24.0D, 64.0D, 6.0D);

    private static final VineId PREY = VineId.of("vine_test", "prey");

    /** Blackboard: ticks spent chasing. */
    private static final BlackboardKey<Integer> CHASE_TICKS = BlackboardKey.intKey("hunt.chaseTicks");

    /** Blackboard: whether the patrol leg is finished. */
    private static final BlackboardKey<Boolean> PATROLLED = BlackboardKey.boolKey("hunt.patrolled");

    /** Blackboard: whether a path to the patrol point is already in flight. */
    private static final BlackboardKey<Boolean> PATROL_PATH = BlackboardKey.boolKey("hunt.patrolPath");

    /** The tick the quarry becomes visible on — fixed, so the trace is a golden. */
    private static final long SIGHTING_TICK = 20L;

    private BrainExemplar() {
    }

    /** Registers the exemplar's blackboard schema (called from the testmod initializer). */
    public static void register() {
        VineData.registerSchema(new VoxelSchema(BRAIN_SCHEMA_ID, 1, CODEC), List.of());
    }

    /** Runs the capture → replay pair and prints the digests, the memory and the trace. */
    public static void run(int ticks) {
        int length = ticks <= 0 ? 120 : ticks;

        TapePrimitives capture = TapePrimitives.recording(scriptedWorld());
        VineBrain first = tree();
        List<String> firstTrace = new ArrayList<>(length);
        for (int tick = 0; tick < length; tick++) {
            firstTrace.add(first.tick(capture).name());
        }

        TapePrimitives replay = TapePrimitives.replaying(capture.calls());
        VineBrain second = tree();
        List<String> secondTrace = new ArrayList<>(length);
        for (int tick = 0; tick < length; tick++) {
            secondTrace.add(second.tick(replay).name());
        }

        String firstDigest = digest(firstTrace, first);
        String secondDigest = digest(secondTrace, second);
        System.out.println("tck: brain ticks=" + length + " calls=" + capture.calls().size()
            + " callDigest=" + capture.digest());
        System.out.println("tck: brain first=" + firstDigest + " replay=" + secondDigest
            + " identical=" + firstDigest.equals(secondDigest));
        System.out.println("tck: brain memory chaseTicks=" + second.blackboard().get(CHASE_TICKS, -1)
            + " patrolled=" + second.blackboard().get(PATROLLED, false));
        System.out.println("tck: brain trace=" + String.join(",", secondTrace));
    }

    /**
     * The actor's tree: patrol until the quarry is visible, then chase it. Both legs are
     * multi-tick actions, so the runtime must genuinely suspend and resume — a
     * single-tick tree would prove nothing.
     */
    private static VineBrain tree() {
        VineBrain brain = VineBrains.of(ACTOR, VineData.create(BRAIN_SCHEMA_ID));

        Node patrol = Action.of("patrol", ctx -> {
            if (ctx.blackboard().get(PATROLLED, false)) {
                return NodeStatus.SUCCESS;
            }
            if (!ctx.blackboard().get(PATROL_PATH, false)) {
                if (ctx.primitives().requestPath(PATROL) != Primitives.PathOutcome.FOUND) {
                    return NodeStatus.FAILURE;
                }
                ctx.blackboard().set(PATROL_PATH, true);
            }
            Primitives.StepOutcome step = ctx.primitives().stepPath();
            if (step == Primitives.StepOutcome.ARRIVED) {
                ctx.blackboard().set(PATROLLED, true);
                ctx.blackboard().remove(PATROL_PATH);
                return NodeStatus.SUCCESS;
            }
            return step == Primitives.StepOutcome.BLOCKED ? NodeStatus.FAILURE : NodeStatus.RUNNING;
        });

        Node chase = Action.of("chase", ctx -> {
            if (!ctx.primitives().hasLineOfSight(PREY)) {
                return NodeStatus.FAILURE;
            }
            ctx.blackboard().set(CHASE_TICKS, ctx.blackboard().get(CHASE_TICKS, 0) + 1);
            ctx.primitives().lookAt(PREY);
            if (ctx.primitives().requestPath(QUARRY) != Primitives.PathOutcome.FOUND) {
                return NodeStatus.FAILURE;
            }
            return ctx.primitives().stepPath() == Primitives.StepOutcome.ARRIVED
                ? NodeStatus.SUCCESS
                : NodeStatus.RUNNING;
        });

        brain.set(brain.stateMachine()
            .initial("wander")
            .state("wander", patrol)
            .state("chase", chase)
            .transition("wander", "chase", ctx -> ctx.tick() >= SIGHTING_TICK && ctx.primitives().hasLineOfSight(PREY))
            .transition("chase", "wander", ctx -> ctx.tick() >= 60)
            .build());
        return brain;
    }

    /**
     * A fixed, scripted world: the patrol leg arrives in three steps, the quarry turns
     * visible on {@link #SIGHTING_TICK}, and the chase leg arrives after three steps.
     * Nothing here reads a clock, so the recording is a golden.
     */
    private static Primitives scriptedWorld() {
        return new Primitives() {

            private int steps;
            private int visionCalls;

            @Override
            public PathOutcome requestPath(Vec3 target) {
                // Every request is satisfied in this world; what changes over time is what
                // the actor can see, not whether it can walk there.
                return PathOutcome.FOUND;
            }

            @Override
            public StepOutcome stepPath() {
                // A fixed function of how many steps have been taken, so the whole world is
                // reproducible from the call sequence alone — nothing here reads a clock.
                return ++steps % 3 == 0 ? StepOutcome.ARRIVED : StepOutcome.ADVANCED;
            }

            @Override
            public boolean hasLineOfSight(VineId target) {
                // The quarry "appears" after a fixed number of looks. Counting calls rather
                // than ticks keeps the world a pure function of what it was asked, which is
                // exactly what a replay can reproduce.
                if (!PREY.equals(target)) {
                    return false;
                }
                return ++visionCalls > SIGHTING_TICK;
            }

            @Override
            public List<VineId> queryTargets(double radius) {
                return List.of(PREY);
            }

            @Override
            public void lookAt(VineId target) {
                // a scripted world has nothing to turn
            }
        };
    }

    /** Statuses plus the final memory, hashed: what the replay must reproduce exactly. */
    private static String digest(List<String> statuses, VineBrain brain) {
        StringBuilder text = new StringBuilder(String.join(",", statuses));
        text.append("|memory:").append(sha256(java.util.Arrays.toString(VineData.encode(brain.blackboard().data()))));
        return sha256(text.toString());
    }

    private static String sha256(String text) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must exist on every supported JVM", e);
        }
    }
}
