package dev.vineengine.vine.animation;

import java.util.List;
import java.util.Map;

import dev.vineengine.vine.world.Vec3;

/**
 * A skeleton evaluated at one instant (sub-09 §2): every bone's model-space transform,
 * plus the two questions gameplay actually asks — where is a point attached to a bone,
 * and what is that bone's orientation.
 *
 * <p>Produced by {@link VineAnimations#pose} from a server tick; consumed by part
 * hosting (sub-08 Stage D) and attack sweeps (sub-10 Stage C). Nothing here knows about
 * rendering: a pose is a value, and the same value is what a client backend would draw
 * from if it wanted to.
 */
public record SkeletonPose(Map<String, BonePose> bones, double seconds) {

    public SkeletonPose {
        bones = Map.copyOf(bones);
    }

    /** One bone's model-space transform. */
    public record BonePose(Vec3 translation, Quaternion rotation, Vec3 scale) {

        public BonePose {
            if (rotation == null) {
                rotation = Quaternion.IDENTITY;
            }
            if (scale == null) {
                scale = Vec3.of(1.0D, 1.0D, 1.0D);
            }
        }
    }

    /** The pose of {@code bone}, or {@code null} when the skeleton has no such bone. */
    public BonePose bone(String bone) {
        return bones.get(bone);
    }

    /**
     * A point attached to {@code bone}: {@code offset} is in the bone's own space, and
     * the result is in the skeleton's model space. This is the primitive bone-attached
     * geometry is built from — a part's centre, a weapon tip, a locator.
     *
     * @throws IllegalArgumentException when the skeleton has no such bone — a
     *         descriptor naming a bone the asset does not have is an authoring bug
     */
    public Vec3 locator(String bone, Vec3 offset) {
        BonePose pose = bones.get(bone);
        if (pose == null) {
            throw new IllegalArgumentException("no bone named '" + bone + "' in this pose (bones: "
                + bones.keySet().stream().sorted().toList() + ")");
        }
        return pose.translation().plus(pose.rotation().rotate(offset));
    }

    /** Every bone name, sorted — the stable order fixtures are written in. */
    public List<String> boneNames() {
        return bones.keySet().stream().sorted().toList();
    }
}
