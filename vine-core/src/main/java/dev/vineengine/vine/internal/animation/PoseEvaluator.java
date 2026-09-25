package dev.vineengine.vine.internal.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.Quaternion;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.animation.TimingWindows;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.world.Vec3;

/**
 * The headless, server-side animation evaluator (sub-09 §2, Stage B): the canonical
 * pose of an asset's clip at a time in seconds, the clip's gameplay windows in server
 * ticks, and a part's world-space oriented box.
 *
 * <p><b>Why this exists rather than GeckoLib.</b> The render library exposes bone world
 * positions only through the render pass and runs animation controllers per render
 * frame, so it cannot be the authoritative source for hitboxes on a dedicated server
 * (sub-09 §4). The evaluator reads the <em>same</em> asset
 * ({@link AnimationAssetParser}) and computes the same transforms from server ticks:
 * the pose here is the one gameplay acts on, and any client interpolation between
 * canonical ticks is presentational only.
 *
 * <p><b>Determinism is the contract.</b> Same asset + same clip + same time ⇒ the same
 * pose, bit for bit, and the golden fixtures compare exact {@code double} values rather
 * than tolerances. Nothing here reads a clock, a frame, or a hash map's iteration order:
 * bones are resolved in sorted name order, parents before children, and every arithmetic
 * step has one fixed order.
 *
 * <p><b>Model space.</b> Bone pivots, as authored, are points in the skeleton's model
 * space. A bone's model-space transform is composed down the parent chain as
 * {@code translation = parentTranslation + parentRotation · (parentScale ⊙ (restPivot −
 * parentRestPivot + animatedPosition))}, {@code rotation = parentRotation then
 * (restRotation + animatedRotation)} and {@code scale = parentScale ⊙ animatedScale} —
 * the nesting the format's own renderer has, expressed as values rather than as a
 * render tree. A bone's local rotation adds to its rest rotation in degrees and is then
 * converted, which is the format's own composition order (its renderer accumulates
 * {@code rest + animated} Euler angles before converting).
 *
 * <p><b>Time.</b> One time base: seconds into the clip, evaluated at a canonical tick
 * (a caller with a tick {@code t} passes {@code t / 20.0}). A looping clip wraps the
 * time into {@code [0, length)}; a non-looping one clamps to {@code [0, length]}. The
 * {@link SkeletonPose} records the effective time, so a fixture that samples past the
 * end says so.
 *
 * <p><b>One format decision stated once:</b> a keyframe segment is interpolated with the
 * easing of the keyframe it <em>starts</em> at (the authoring tool hangs the easing on the
 * outgoing keyframe), and outside a channel's own span its nearest value is held. The exact
 * curves, including the Catmull-Rom tangents, are stated on {@link #sample} because the
 * goldens pin them bit for bit.
 *
 * <p><b>No client type is touched</b> — this class is loaded and run by a plain-JVM
 * harness whose classpath is {@code vine-api} + {@code vine-core} + their libraries,
 * with no Minecraft, loader or LWJGL jar on it (sub-09 §5).
 */
public final class PoseEvaluator {

    /** Ticks per second on every cell: the time base windows and samples are expressed in. */
    public static final double TICKS_PER_SECOND = 20.0D;

    /** Marker that opens the clip's active (connectable) window. */
    public static final String ACTIVE_START_MARKER = "vine:active_start";
    /** Marker that closes the active window; absent means "to the end of the clip". */
    public static final String ACTIVE_END_MARKER = "vine:active_end";
    /** Marker from which the clip may be cancelled into another action. */
    public static final String CANCEL_START_MARKER = "vine:cancel_start";

    /** The rest scale of a bone with no animated scale channel. */
    private static final Vec3 UNIT = Vec3.of(1.0D, 1.0D, 1.0D);
    /** Marker times are compared against the clip length with this slack (authoring floats). */
    private static final double TIME_EPSILON = 1.0e-9D;

    private PoseEvaluator() {
    }

    /**
     * Evaluates {@code clip} of {@code asset} at {@code seconds} into the clip.
     *
     * @param asset       a parsed asset
     * @param clip        the clip's name; an unknown name is a caller bug, reported with
     *                    the names the asset does have
     * @param seconds     seconds into the clip — a server tick {@code t} is {@code t / 20.0}
     * @return every bone's model-space transform, plus the effective time inside the clip
     * @throws IllegalArgumentException when the asset has no such clip, or when {@code seconds}
     *         is not finite
     */
    public static SkeletonPose pose(AnimationAsset asset, String clip, double seconds) {
        return evaluator(asset, clip).evaluate(seconds);
    }

    /**
     * The gameplay windows {@code clip} declares, in server ticks at 20 TPS.
     *
     * <p>The convention the evaluator reads, and the whole of it: {@code vine:active_start}
     * opens the active window, {@code vine:active_end} closes it, {@code vine:cancel_start}
     * is the first tick the clip may be cancelled from. Marker times snap to the nearest
     * canonical tick ({@code round(seconds × 20)}), so the asset states timing in the same
     * units gameplay reads it in. A clip that declares no marker at all yields a
     * recovery-only window over its full length — the honest reading of "nobody declared a
     * hit window here" that {@link TimingWindows} documents. A clip that declares some of
     * them reads the rest from the motion it does describe: a missing {@code active_start}
     * opens the window at tick 0, a missing {@code active_end} runs it to the clip's end, and
     * a missing {@code cancel_start} cancels from the end of the active window.
     *
     * @throws IllegalArgumentException when the asset has no such clip, when a marker sits
     *         outside the clip, or when the declared markers describe a window that cannot
     *         exist (an active end before its start, an active window past the clip)
     */
    public static TimingWindows windows(AnimationAsset asset, String clip) {
        AnimationAsset.Clip declared = requireClip(asset, clip);
        double length = declared.lengthSeconds();
        int total = tick(length);
        if (total < 1) {
            throw new IllegalArgumentException("asset '" + asset.name() + "': clip '" + clip
                + "' is " + Double.toString(length) + "s long, which is not one whole server tick —"
                + " a clip gameplay can never act on");
        }
        Double activeStart = markerTime(asset, declared, ACTIVE_START_MARKER);
        Double activeEnd = markerTime(asset, declared, ACTIVE_END_MARKER);
        Double cancelStart = markerTime(asset, declared, CANCEL_START_MARKER);
        if (activeStart == null && activeEnd == null && cancelStart == null) {
            // The author declared no window at all: the clip commits to nothing, so it is
            // recovery for its whole length — the reading TimingWindows documents.
            return new TimingWindows(0, 0, 0, total);
        }
        int startup = activeStart == null ? 0 : tick(activeStart);
        int end = activeEnd == null ? total : tick(activeEnd);
        if (end < startup) {
            throw new IllegalArgumentException("asset '" + asset.name() + "': clip '" + clip + "' declares "
                + ACTIVE_END_MARKER + " at " + Double.toString(activeEnd) + "s (tick " + end + ") before "
                + ACTIVE_START_MARKER + " at " + Double.toString(activeStart) + "s (tick " + startup
                + ") — an authoring bug, so no window is guessed");
        }
        int active = end - startup;
        if (end > total) {
            throw new IllegalArgumentException("asset '" + asset.name() + "': clip '" + clip
                + "' declares an active window ending at tick " + end + " but the clip is only " + total
                + " tick(s) long — an authoring bug, so no window is clamped silently");
        }
        int cancel = cancelStart == null ? startup + active : tick(cancelStart);
        return new TimingWindows(startup, active, cancel, total);
    }

    /**
     * A part's world-space oriented box at {@code pose} (sub-08 Stage D's interface).
     *
     * <p>The part's {@code offset} and {@code size} are in bone-local axes, so the box's
     * centre is the bone's model-space translation plus its rotation applied to the offset
     * scaled by the bone's scale, its half-extents are half the size scaled by the bone's
     * scale, and its rotation is the bone's model-space rotation composed with the actor's
     * yaw. A non-uniform ancestor scale combined with a rotation between those frames is
     * the one thing a single oriented box cannot represent exactly; the format's clips use
     * uniform scale, and this method is exact whenever they do.
     *
     * <p><b>Yaw convention:</b> degrees, in the cell's own convention — yaw {@code 0} has
     * the actor's model-space {@code +Z} pointing at world {@code +Z}, and the yaw angle
     * increases clockwise seen from above, so a model-space point {@code p} reaches the
     * world at {@code actorPosition + Ry(−yaw) · p}.
     *
     * @throws IllegalArgumentException when the pose has no bone named by the part — a
     *         descriptor naming a bone the asset does not have is an authoring bug, and the
     *         message lists the bones that do exist
     */
    public static OrientedBox partBox(SkeletonPose pose, PartDescriptor part, Vec3 actorPosition,
            float actorYawDegrees) {
        Objects.requireNonNull(pose, "pose");
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(actorPosition, "actorPosition");
        SkeletonPose.BonePose bonePose = pose.bone(part.parentBone());
        if (bonePose == null) {
            throw new IllegalArgumentException("part '" + part.name() + "' is attached to bone '"
                + part.parentBone() + "', which this pose does not contain (bones: " + pose.boneNames()
                + ")");
        }
        Vec3 scale = bonePose.scale();
        Vec3 modelCenter = bonePose.translation()
            .plus(bonePose.rotation().rotate(multiply(part.offset(), scale)));
        Quaternion yaw = Quaternion.fromEulerDegrees(Vec3.of(0.0D, -actorYawDegrees, 0.0D));
        return new OrientedBox(
            actorPosition.plus(yaw.rotate(modelCenter)),
            Vec3.of(part.size().x() * scale.x() * 0.5D, part.size().y() * scale.y() * 0.5D,
                part.size().z() * scale.z() * 0.5D),
            bonePose.rotation().then(yaw));
    }

    private static Evaluator evaluator(AnimationAsset asset, String clip) {
        return new Evaluator(Objects.requireNonNull(asset, "asset"), requireClip(asset, clip));
    }

    static AnimationAsset.Clip requireClip(AnimationAsset asset, String clip) {
        Objects.requireNonNull(asset, "asset");
        if (clip == null || clip.isBlank()) {
            throw new IllegalArgumentException("pose/windows need a clip name; asset '" + asset.name()
                + "' has " + asset.clips().keySet());
        }
        AnimationAsset.Clip declared = asset.clips().get(clip);
        if (declared == null) {
            throw new IllegalArgumentException("asset '" + asset.name() + "' has no clip named '" + clip
                + "' (clips: " + asset.clips().keySet() + ")");
        }
        return declared;
    }

    /** One marker's time, validated against the clip, or {@code null} when it is absent. */
    private static Double markerTime(AnimationAsset asset, AnimationAsset.Clip clip, String marker) {
        Double found = null;
        for (AnimationAsset.Marker candidate : clip.markers()) {
            if (!candidate.name().equals(marker)) {
                continue;
            }
            if (found != null) {
                throw new IllegalArgumentException("asset '" + asset.name() + "': clip '" + clip.name()
                    + "' declares '" + marker + "' twice (" + Double.toString(found) + "s and "
                    + Double.toString(candidate.timeSeconds()) + "s) — an authoring bug, since the"
                    + " window edge would be order-dependent");
            }
            found = candidate.timeSeconds();
        }
        if (found == null) {
            return null;
        }
        if (found < 0.0D || found > clip.lengthSeconds() + TIME_EPSILON) {
            throw new IllegalArgumentException("asset '" + asset.name() + "': clip '" + clip.name()
                + "' declares '" + marker + "' at " + Double.toString(found) + "s, outside the clip's "
                + Double.toString(clip.lengthSeconds()) + "s — an authoring bug");
        }
        return found;
    }

    /** A marker time as the canonical tick it names: the nearest one, at 20 TPS. */
    private static int tick(double seconds) {
        return (int) Math.round(seconds * TICKS_PER_SECOND);
    }

    // ------------------------------------------------------------------
    // per-(asset, clip) evaluation
    // ------------------------------------------------------------------

    /**
     * One clip's evaluation. Bones are held in sorted name order and resolved parent-first,
     * so the arithmetic order — and therefore every bit of the result — is a property of the
     * asset's names, not of a hash map.
     */
    private static final class Evaluator {

        private final AnimationAsset asset;
        private final AnimationAsset.Clip clip;
        private final List<String> names;
        private final Map<String, SkeletonPose.BonePose> resolved = new TreeMap<>();

        Evaluator(AnimationAsset asset, AnimationAsset.Clip clip) {
            this.asset = asset;
            this.clip = clip;
            this.names = new ArrayList<>(asset.bones().keySet());
            this.names.sort(null);
        }

        SkeletonPose evaluate(double seconds) {
            if (!Double.isFinite(seconds)) {
                throw new IllegalArgumentException("asset '" + asset.name() + "': clip '" + clip.name()
                    + "' cannot be evaluated at " + seconds + "s — a time must be finite");
            }
            double time = effectiveTime(seconds);
            for (String name : names) {
                resolve(name, time, 0);
            }
            return new SkeletonPose(resolved, time);
        }

        /** A looping clip wraps; a one-shot clip clamps to its own span. */
        private double effectiveTime(double seconds) {
            double length = clip.lengthSeconds();
            if (!clip.loop()) {
                return Math.min(Math.max(seconds, 0.0D), length);
            }
            double wrapped = seconds % length;
            return wrapped < 0.0D ? wrapped + length : wrapped;
        }

        private SkeletonPose.BonePose resolve(String name, double time, int depth) {
            SkeletonPose.BonePose cached = resolved.get(name);
            if (cached != null) {
                return cached;
            }
            if (depth > names.size()) {
                throw new IllegalArgumentException("asset '" + asset.name() + "': bone '" + name
                    + "' has a cyclic parent chain — no pose exists for it");
            }
            AnimationAsset.Bone bone = asset.bones().get(name);
            if (bone == null) {
                throw new IllegalArgumentException("asset '" + asset.name() + "': clip '" + clip.name()
                    + "' reaches bone '" + name + "', which the geometry does not declare (bones: "
                    + names + ")");
            }
            Vec3 parentTranslation = Vec3.ZERO;
            Quaternion parentRotation = Quaternion.IDENTITY;
            Vec3 parentScale = UNIT;
            Vec3 parentPivot = Vec3.ZERO;
            if (!bone.isRoot()) {
                AnimationAsset.Bone declaredParent = asset.bones().get(bone.parent());
                if (declaredParent == null) {
                    throw new IllegalArgumentException("asset '" + asset.name() + "': bone '" + name
                        + "' names parent '" + bone.parent() + "', which the geometry does not declare"
                        + " (bones: " + names + ")");
                }
                SkeletonPose.BonePose parentPose = resolve(declaredParent.name(), time, depth + 1);
                parentTranslation = parentPose.translation();
                parentRotation = parentPose.rotation();
                parentScale = parentPose.scale();
                parentPivot = declaredParent.pivot();
            }
            AnimationAsset.Channel channel = clip.channel(name);
            Vec3 position = channel == null ? Vec3.ZERO
                : sample(channel.position(), time, Vec3.ZERO);
            Vec3 rotation = channel == null ? Vec3.ZERO
                : sample(channel.rotation(), time, Vec3.ZERO);
            Vec3 scale = channel == null ? UNIT : sample(channel.scale(), time, UNIT);

            Vec3 offset = Vec3.of(
                bone.pivot().x() - parentPivot.x() + position.x(),
                bone.pivot().y() - parentPivot.y() + position.y(),
                bone.pivot().z() - parentPivot.z() + position.z());
            SkeletonPose.BonePose pose = new SkeletonPose.BonePose(
                parentTranslation.plus(parentRotation.rotate(multiply(offset, parentScale))),
                parentRotation.then(Quaternion.fromEulerDegrees(bone.rotation().plus(rotation))),
                multiply(parentScale, scale));
            resolved.put(name, pose);
            return pose;
        }
    }

    // ------------------------------------------------------------------
    // keyframe interpolation
    // ------------------------------------------------------------------

    /**
     * One channel's value at {@code time}, interpolated with the easing of the keyframe the
     * segment starts at. Outside the channel's span the nearest keyframe's value is held.
     *
     * <p>The easing curves, stated exactly because the fixtures pin them: {@code LINEAR} is
     * the straight interpolation; {@code EASE_IN} is {@code α²}; {@code EASE_OUT} is
     * {@code 1 − (1 − α)²}; {@code EASE_IN_OUT} is {@code α²} up to the midpoint and
     * {@code 1 − 2(1 − α)²} after it; {@code STEP} holds the segment's first value; and
     * {@code CATMULLROM} is the cubic Hermite spline through the neighbouring keyframes with
     * finite-difference tangents,
     * {@code p(s) = h00·v0 + h10·h·m0 + h01·v1 + h11·h·m1} where {@code m0 = (v1 − v−1)/(t1 −
     * t−1)}, {@code m1 = (v+1 − v0)/(t+1 − t0)} (a missing neighbour reuses the nearest
     * keyframe) and {@code h00 = 2s³ − 3s² + 1}, {@code h10 = s³ − 2s² + s},
     * {@code h01 = −2s³ + 3s²}, {@code h11 = s³ − s²}.
     */
    private static Vec3 sample(List<AnimationAsset.Keyframe> keyframes, double time, Vec3 rest) {
        if (keyframes.isEmpty()) {
            return rest;
        }
        AnimationAsset.Keyframe first = keyframes.get(0);
        if (time <= first.timeSeconds()) {
            return first.value();
        }
        AnimationAsset.Keyframe last = keyframes.get(keyframes.size() - 1);
        if (time >= last.timeSeconds()) {
            return last.value();
        }
        int lower = 0;
        for (int i = 0; i < keyframes.size() - 1; i++) {
            if (keyframes.get(i).timeSeconds() <= time && time < keyframes.get(i + 1).timeSeconds()) {
                lower = i;
                break;
            }
        }
        AnimationAsset.Keyframe from = keyframes.get(lower);
        AnimationAsset.Keyframe to = keyframes.get(lower + 1);
        double span = to.timeSeconds() - from.timeSeconds();
        if (span <= 0.0D) {
            // Only reachable for a hand-built asset that bypassed the parser's duplicate-time
            // rejection; a zero-length segment has no interpolation to do.
            return to.value();
        }
        double alpha = (time - from.timeSeconds()) / span;
        return switch (from.easing()) {
            case STEP -> from.value();
            case LINEAR -> interpolate(from.value(), to.value(), alpha);
            case EASE_IN -> interpolate(from.value(), to.value(), alpha * alpha);
            case EASE_OUT -> interpolate(from.value(), to.value(), alpha * (2.0D - alpha));
            case EASE_IN_OUT -> interpolate(from.value(), to.value(),
                alpha < 0.5D ? 2.0D * alpha * alpha : 1.0D - 2.0D * (1.0D - alpha) * (1.0D - alpha));
            case CATMULLROM -> catmullRom(keyframes, lower, alpha, span);
        };
    }

    /**
     * The format's smooth curve: a cubic Hermite through the two neighbouring keyframes
     * either side of the segment, with finite-difference tangents. Stated as a formula rather
     * than delegated to a library so the canonical pose is a property of this file.
     */
    private static Vec3 catmullRom(List<AnimationAsset.Keyframe> keyframes, int lower, double alpha,
            double span) {
        AnimationAsset.Keyframe v0 = keyframes.get(lower);
        AnimationAsset.Keyframe v1 = keyframes.get(lower + 1);
        AnimationAsset.Keyframe before = lower > 0 ? keyframes.get(lower - 1) : null;
        AnimationAsset.Keyframe after = lower + 2 < keyframes.size() ? keyframes.get(lower + 2) : null;
        Vec3 valueBefore = before == null ? v0.value() : before.value();
        Vec3 valueAfter = after == null ? v1.value() : after.value();
        double timeBefore = before == null ? v0.timeSeconds() : before.timeSeconds();
        double timeAfter = after == null ? v1.timeSeconds() : after.timeSeconds();
        Vec3 tangent0 = divide(subtract(v1.value(), valueBefore), v1.timeSeconds() - timeBefore);
        Vec3 tangent1 = divide(subtract(valueAfter, v0.value()), timeAfter - v0.timeSeconds());
        double squared = alpha * alpha;
        double cubed = squared * alpha;
        double h00 = 2.0D * cubed - 3.0D * squared + 1.0D;
        double h10 = cubed - 2.0D * squared + alpha;
        double h01 = -2.0D * cubed + 3.0D * squared;
        double h11 = cubed - squared;
        return Vec3.of(
            h00 * v0.value().x() + h10 * span * tangent0.x() + h01 * v1.value().x() + h11 * span * tangent1.x(),
            h00 * v0.value().y() + h10 * span * tangent0.y() + h01 * v1.value().y() + h11 * span * tangent1.y(),
            h00 * v0.value().z() + h10 * span * tangent0.z() + h01 * v1.value().z() + h11 * span * tangent1.z());
    }

    private static Vec3 interpolate(Vec3 from, Vec3 to, double alpha) {
        return Vec3.of(
            from.x() + (to.x() - from.x()) * alpha,
            from.y() + (to.y() - from.y()) * alpha,
            from.z() + (to.z() - from.z()) * alpha);
    }

    private static Vec3 subtract(Vec3 a, Vec3 b) {
        return Vec3.of(a.x() - b.x(), a.y() - b.y(), a.z() - b.z());
    }

    private static Vec3 divide(Vec3 value, double divisor) {
        return Vec3.of(value.x() / divisor, value.y() / divisor, value.z() / divisor);
    }

    private static Vec3 multiply(Vec3 a, Vec3 b) {
        return Vec3.of(a.x() * b.x(), a.y() * b.y(), a.z() * b.z());
    }
}
