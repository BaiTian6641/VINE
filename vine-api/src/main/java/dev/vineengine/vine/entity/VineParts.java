package dev.vineengine.vine.entity;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.PartBackend;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * Static entry point for multipart entities (sub-08 Stage D). Mirror of the other
 * facades: parts are the engine's, the actor's body is the cell's, and this is where a
 * consumer asks about the engine's half.
 *
 * <p>A part's state lives in the actor's own stored tree (opened through the cell's
 * attach point), so wound state persists exactly like every other piece of engine data;
 * its geometry comes from the sub-09 evaluator, so a part is where its bone is, not
 * where a model file says it looks.
 */
public final class VineParts {

    private VineParts() {
    }

    /**
     * Starts pose-driven part hosting for {@code ref}: its declared parts become live,
     * driven by {@code clip} of {@code asset}.
     *
     * @throws IllegalStateException if hosting is already attached, or the running cell
     *         cannot supply the actor's storage attach point
     */
    public static void attach(VineEntityRef ref, AnimationAsset asset, String clip) {
        Objects.requireNonNull(ref, "ref");
        backend().attach(ref, Objects.requireNonNull(asset, "asset"), Objects.requireNonNull(clip, "clip"));
    }

    /** Stops hosting {@code ref}'s parts; its stored wound state stays where it is. */
    public static void detach(VineEntityRef ref) {
        backend().detachParts(ref);
    }

    /** Whether {@code ref} has live parts. */
    public static boolean isHosted(VineEntityRef ref) {
        return backend().isHosted(ref);
    }

    /** Switches the clip driving the parts, starting it at {@code tick}. */
    public static void play(VineEntityRef ref, String clip, long tick) {
        backend().play(ref, clip, tick);
    }

    /** Every part's state, in descriptor order. */
    public static List<PartState> parts(VineEntityRef ref) {
        return backend().parts(ref);
    }

    /** One part's state. */
    public static Optional<PartState> part(VineEntityRef ref, String name) {
        return backend().part(ref, name);
    }

    /**
     * The world-space box of {@code name} for an actor standing at {@code position}
     * facing {@code yawDegrees} — the geometry a cell hosts and a sweep tests.
     */
    public static Optional<OrientedBox> box(VineEntityRef ref, String name, Vec3 position, float yawDegrees) {
        return backend().box(ref, name, position, yawDegrees);
    }

    /** The current pose driving {@code ref}'s parts. */
    public static Optional<SkeletonPose> pose(VineEntityRef ref) {
        return backend().pose(ref);
    }

    /**
     * Applies one hit to one part and reports what changed: how much damage the part's
     * multiplier made of it, whether this hit broke the part, and whether it flinched.
     *
     * @return the outcome, or empty when the actor has no such part (a caller bug on a
     *         named part, reported rather than guessed)
     */
    public static Optional<PartHit> applyHit(VineEntityRef ref, String name, double amount, VineId damageType) {
        return backend().applyHit(ref, Objects.requireNonNull(name, "name"), amount,
            Objects.requireNonNull(damageType, "damageType"));
    }

    /**
     * Takes the pending flinch for {@code ref}, if a part crossed its flinch threshold
     * since the last call — the signal a behaviour reads to interrupt what it is doing.
     * Consuming rather than reading, so a flinch interrupts exactly once.
     */
    public static boolean consumeFlinch(VineEntityRef ref) {
        return backend().consumeFlinch(ref);
    }

    /** What one hit produced. */
    public record PartHit(String name, double rawAmount, double appliedAmount, boolean broke, boolean flinched,
            PartState state) {

        public PartHit {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(state, "state");
        }
    }

    private static PartBackend backend() {
        if (EngineAccess.get() instanceof PartBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide multipart hosting (headless runtime, or vine-core is not the"
                + " engine)");
    }
}
