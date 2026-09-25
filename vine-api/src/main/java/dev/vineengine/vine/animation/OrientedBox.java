package dev.vineengine.vine.animation;

import dev.vineengine.vine.world.Vec3;

/**
 * An oriented box in world space (sub-09 §2): a part's collider, or a sweep volume,
 * expressed as a centre, half-extents and a rotation. Produced from a
 * {@link SkeletonPose} plus a {@link dev.vineengine.vine.entity.PartDescriptor}, and
 * consumed by sub-08's part hosts and sub-10's sweep query.
 *
 * <p>Oriented rather than axis-aligned because a tail or a wing is not axis-aligned
 * when its bone is mid-swing, and approximating it with an AABB is exactly the
 * inaccuracy that makes per-part hitboxes feel wrong.
 */
public record OrientedBox(Vec3 center, Vec3 halfExtents, Quaternion rotation) {

    public OrientedBox {
        if (rotation == null) {
            rotation = Quaternion.IDENTITY;
        }
    }

    /** Every corner, in a stable order (sign bits ascending) — for traces and fixtures. */
    public Vec3[] corners() {
        Vec3[] out = new Vec3[8];
        int i = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    Vec3 local = Vec3.of(sx * halfExtents.x(), sy * halfExtents.y(), sz * halfExtents.z());
                    out[i++] = center.plus(rotation.rotate(local));
                }
            }
        }
        return out;
    }

    /** Whether {@code point} is inside this box (a boundary counts as inside). */
    public boolean contains(Vec3 point) {
        Vec3 local = rotation.conjugate().rotate(Vec3.of(point.x() - center.x(), point.y() - center.y(),
            point.z() - center.z()));
        return Math.abs(local.x()) <= halfExtents.x() + 1.0e-9D
            && Math.abs(local.y()) <= halfExtents.y() + 1.0e-9D
            && Math.abs(local.z()) <= halfExtents.z() + 1.0e-9D;
    }

    /**
     * Whether this box overlaps {@code other}, by the separating-axis test.
     *
     * <p>Exact rather than conservative: a sweep query that reports hits the geometry
     * does not have is a bug a player cannot distinguish from bad hitboxes.
     */
    public boolean intersects(OrientedBox other) {
        Vec3[] axes = new Vec3[15];
        Vec3 a1 = rotation.rotate(Vec3.of(1.0D, 0.0D, 0.0D));
        Vec3 a2 = rotation.rotate(Vec3.of(0.0D, 1.0D, 0.0D));
        Vec3 a3 = rotation.rotate(Vec3.of(0.0D, 0.0D, 1.0D));
        Vec3 b1 = other.rotation().rotate(Vec3.of(1.0D, 0.0D, 0.0D));
        Vec3 b2 = other.rotation().rotate(Vec3.of(0.0D, 1.0D, 0.0D));
        Vec3 b3 = other.rotation().rotate(Vec3.of(0.0D, 0.0D, 1.0D));
        axes[0] = a1;
        axes[1] = a2;
        axes[2] = a3;
        axes[3] = b1;
        axes[4] = b2;
        axes[5] = b3;
        int next = 6;
        for (Vec3 a : new Vec3[] {a1, a2, a3}) {
            for (Vec3 b : new Vec3[] {b1, b2, b3}) {
                axes[next++] = Vec3.of(
                    a.y() * b.z() - a.z() * b.y(),
                    a.z() * b.x() - a.x() * b.z(),
                    a.x() * b.y() - a.y() * b.x());
            }
        }
        Vec3 d = Vec3.of(other.center().x() - center.x(), other.center().y() - center.y(),
            other.center().z() - center.z());
        for (Vec3 axis : axes) {
            double length = Math.sqrt(axis.x() * axis.x() + axis.y() * axis.y() + axis.z() * axis.z());
            if (length < 1.0e-9D) {
                continue;
            }
            Vec3 n = Vec3.of(axis.x() / length, axis.y() / length, axis.z() / length);
            double projectedDistance = Math.abs(d.x() * n.x() + d.y() * n.y() + d.z() * n.z());
            double projectedA = Math.abs(a1.x() * n.x() + a1.y() * n.y() + a1.z() * n.z()) * halfExtents.x()
                + Math.abs(a2.x() * n.x() + a2.y() * n.y() + a2.z() * n.z()) * halfExtents.y()
                + Math.abs(a3.x() * n.x() + a3.y() * n.y() + a3.z() * n.z()) * halfExtents.z();
            double projectedB = Math.abs(b1.x() * n.x() + b1.y() * n.y() + b1.z() * n.z()) * other.halfExtents().x()
                + Math.abs(b2.x() * n.x() + b2.y() * n.y() + b2.z() * n.z()) * other.halfExtents().y()
                + Math.abs(b3.x() * n.x() + b3.y() * n.y() + b3.z() * n.z()) * other.halfExtents().z();
            if (projectedDistance > projectedA + projectedB + 1.0e-9D) {
                return false;
            }
        }
        return true;
    }
}
