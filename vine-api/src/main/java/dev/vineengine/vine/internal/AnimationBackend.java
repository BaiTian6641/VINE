package dev.vineengine.vine.internal;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.animation.TimingWindows;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.world.Vec3;

/**
 * Internal bridge from {@code VineAnimations} (vine-api) to vine-core's headless
 * evaluator. NOT public API — implemented once by vine-core's engine object; never
 * implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess}, so the engine's exactly-one-provider rule,
 * boot ordering and cached boot failure apply to evaluation unchanged (same pattern as
 * {@link VoxelBackend}, {@link WorldBackend}, {@link EntityBackend} and
 * {@link BrainBackend}).
 */
public interface AnimationBackend {

    /** See {@code VineAnimations#parse}. */
    AnimationAsset parse(String assetJson);

    /** See {@code VineAnimations#pose}. */
    SkeletonPose pose(AnimationAsset asset, String clip, double seconds);

    /** See {@code VineAnimations#windows}. */
    TimingWindows windows(AnimationAsset asset, String clip);

    /** See {@code VineAnimations#partBox}. */
    OrientedBox partBox(SkeletonPose pose, PartDescriptor part, Vec3 actorPosition, float actorYawDegrees);
}
