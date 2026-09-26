package dev.vineengine.vine.internal;

import java.util.List;
import java.util.Optional;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * Internal bridge from {@code VineParts} (vine-api) to vine-core's multipart runtime.
 * NOT public API — implemented once by vine-core's engine object; never implemented or
 * referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess}, so the engine's exactly-one-provider rule,
 * boot ordering and cached boot failure apply unchanged (same pattern as the other six
 * backends).
 */
public interface PartBackend {

    /** See {@code VineParts#attach}. */
    void attach(VineEntityRef ref, AnimationAsset asset, String clip);

    /** See {@code VineParts#detach}. */
    void detachParts(VineEntityRef ref);

    /** See {@code VineParts#isHosted}. */
    boolean isHosted(VineEntityRef ref);

    /** See {@code VineParts#play}. */
    void play(VineEntityRef ref, String clip, long tick);

    /** See {@code VineParts#parts}. */
    List<PartState> parts(VineEntityRef ref);

    /** See {@code VineParts#part}. */
    Optional<PartState> part(VineEntityRef ref, String name);

    /** See {@code VineParts#box}. */
    Optional<OrientedBox> box(VineEntityRef ref, String name, Vec3 position, float yawDegrees);

    /** See {@code VineParts#pose}. */
    Optional<SkeletonPose> pose(VineEntityRef ref);

    /** See {@code VineParts#applyHit}. */
    Optional<VineParts.PartHit> applyHit(VineEntityRef ref, String name, double amount, VineId damageType);

    /** See {@code VineParts#consumeFlinch}. */
    boolean consumeFlinch(VineEntityRef ref);
}
