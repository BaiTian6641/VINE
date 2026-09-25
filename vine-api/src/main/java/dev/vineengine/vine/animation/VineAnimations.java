package dev.vineengine.vine.animation;

import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.internal.AnimationBackend;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.world.Vec3;

/**
 * Static entry point for animation evaluation (sub-09 §2). Mirror of the other engine
 * facades: the call is a thin surface over the engine's own headless evaluator, so a
 * consumer never parses an asset itself and never needs a renderer to ask where a bone
 * is.
 *
 * <p><b>Server-side and deterministic:</b> every method here is pure — same asset and
 * same tick produce the same pose, bit for bit — and none of them touches a client
 * class. That is what lets combat timing and per-bone hitboxes be computed by the
 * server without a client in the loop, and what the golden fixtures check.
 */
public final class VineAnimations {

    private VineAnimations() {
    }

    /**
     * Parses an asset from its authoring JSON.
     *
     * @throws IllegalArgumentException when the JSON is malformed or names a bone whose
     *         parent does not exist — an authoring bug, reported where it is legible
     */
    public static AnimationAsset parse(String assetJson) {
        return backend().parse(assetJson);
    }

    /** Evaluates {@code clip} of {@code asset} at {@code seconds} into the clip. */
    public static SkeletonPose pose(AnimationAsset asset, String clip, double seconds) {
        return backend().pose(asset, clip, seconds);
    }

    /** The gameplay windows {@code clip} declares, in server ticks. */
    public static TimingWindows windows(AnimationAsset asset, String clip) {
        return backend().windows(asset, clip);
    }

    /**
     * The world-space box of {@code part} at {@code pose}, for an actor standing at
     * {@code actorPosition} with {@code actorYawDegrees} — the geometry sub-08's part
     * hosts and sub-10's sweeps consume.
     */
    public static OrientedBox partBox(SkeletonPose pose, PartDescriptor part, Vec3 actorPosition,
            float actorYawDegrees) {
        return backend().partBox(pose, part, actorPosition, actorYawDegrees);
    }

    private static AnimationBackend backend() {
        if (EngineAccess.get() instanceof AnimationBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide animation evaluation (headless runtime, or vine-core is not the"
                + " engine)");
    }
}
