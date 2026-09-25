package dev.vineengine.vine.animation;

import dev.vineengine.vine.world.Vec3;

/**
 * A rotation as a unit quaternion (sub-09 §2). Quaternions rather than Euler angles
 * because a pose is composed down a bone chain and interpolated across keyframes, and
 * both operations are well-behaved here and ill-behaved for Euler angles (gimbal lock,
 * order dependence). Axes follow the authoring format's own convention, in degrees at
 * the boundary and radians inside.
 *
 * <p><b>Invariants:</b> normalised on construction; {@link #IDENTITY} is no rotation.
 * Immutable.
 */
public record Quaternion(double x, double y, double z, double w) {

    /** No rotation. */
    public static final Quaternion IDENTITY = new Quaternion(0.0D, 0.0D, 0.0D, 1.0D);

    /** From Euler angles in <em>degrees</em>, in the format's ZYX order. */
    public static Quaternion fromEulerDegrees(Vec3 degrees) {
        double hx = Math.toRadians(degrees.x()) * 0.5D;
        double hy = Math.toRadians(degrees.y()) * 0.5D;
        double hz = Math.toRadians(degrees.z()) * 0.5D;
        double cx = Math.cos(hx);
        double sx = Math.sin(hx);
        double cy = Math.cos(hy);
        double sy = Math.sin(hy);
        double cz = Math.cos(hz);
        double sz = Math.sin(hz);
        return new Quaternion(
            sx * cy * cz - cx * sy * sz,
            cx * sy * cz + sx * cy * sz,
            cx * cy * sz - sx * sy * cz,
            cx * cy * cz + sx * sy * sz);
    }

    public Quaternion {
        double length = Math.sqrt(x * x + y * y + z * z + w * w);
        if (length == 0.0D) {
            throw new IllegalArgumentException("Quaternion must not be the zero quaternion");
        }
        if (Math.abs(length - 1.0D) > 1.0e-12D) {
            x /= length;
            y /= length;
            z /= length;
            w /= length;
        }
    }

    /** This rotation applied to {@code v}. */
    public Vec3 rotate(Vec3 v) {
        // v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + q.w * v)
        double cx = y * v.z() - z * v.y() + w * v.x();
        double cy = z * v.x() - x * v.z() + w * v.y();
        double cz = x * v.y() - y * v.x() + w * v.z();
        return Vec3.of(
            v.x() + 2.0D * (y * cz - z * cy),
            v.y() + 2.0D * (z * cx - x * cz),
            v.z() + 2.0D * (x * cy - y * cx));
    }

    /** The inverse rotation (a unit quaternion's conjugate). */
    public Quaternion conjugate() {
        return new Quaternion(-x, -y, -z, w);
    }

    /**
     * Composes two rotations such that {@code other} is applied <em>first</em>, then
     * this one: {@code parent.then(local)} is the usual bone-chain composition, where a
     * bone's own rotation happens in its parent's frame. (The name reads oddly in
     * isolation; it is the standard {@code q1 * q2} product, in which the right operand
     * acts first. A backend that composed the other way round would draw a different
     * pose than gameplay acts on, which is exactly the bug the goldens pin.)
     */
    public Quaternion then(Quaternion other) {
        return new Quaternion(
            w * other.x + x * other.w + y * other.z - z * other.y,
            w * other.y - x * other.z + y * other.w + z * other.x,
            w * other.z + x * other.y - y * other.x + z * other.w,
            w * other.w - x * other.x - y * other.y - z * other.z);
    }
}
