package dev.vineengine.vine.combat;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.world.Vec3;

/**
 * The volume a strike sweeps (sub-10 §2): where an attack looks for targets, in the
 * attacker's own model space, before any target's parts are consulted.
 *
 * <p>Two shapes cover the v1 cases and nothing more. A {@link Box} is the general case —
 * a weapon's reach, or a tail's arc approximated by an oriented box. An {@link Arc} is
 * what weapon mods call an arc hitbox: a box whose horizontal span is bounded by an angle
 * around the attacker's facing, which is how "a wide swing misses what stands behind me"
 * becomes a rule rather than a hope.
 *
 * <p>Sealed on purpose: the sweep is a closed set, so a cell can never be asked to
 * interpret a shape the engine does not itself compute.
 */
public sealed interface SweepShape permits SweepShape.Box, SweepShape.Arc {

    /** The shape's extent in the attacker's model space. */
    Vec3 halfExtents();

    /** The shape's centre, in the attacker's model space (usually in front of the eyes). */
    Vec3 offset();

    /**
     * The shape's furthest horizontal extent from the attacker's own position — the
     * number a sanity check uses to refuse an absurd reach before any target is examined.
     */
    default double reach() {
        Vec3 extents = halfExtents();
        Vec3 centre = offset();
        return Math.hypot(centre.x(), centre.z()) + Math.hypot(extents.x(), extents.z());
    }

    /** How far the shape leans forward of the actor, in blocks. */
    default double forward() {
        return -offset().z();
    }

    /** A fully general oriented box. */
    record Box(Vec3 halfExtents, Vec3 offset) implements SweepShape {

        public Box {
            Objects.requireNonNull(halfExtents, "halfExtents");
            Objects.requireNonNull(offset, "offset");
            positive(halfExtents);
        }

        /** A box {@code reach} blocks long in front of the actor, {@code halfWidth} to each side. */
        public static Box of(double reach, double halfWidth, double halfHeight) {
            return new Box(Vec3.of(halfWidth, halfHeight, reach * 0.5D), Vec3.of(0.0D, 0.0D, -reach * 0.5D));
        }
    }

    /** A box whose horizontal span is bounded by an angle around the attacker's facing. */
    record Arc(Vec3 halfExtents, Vec3 offset, double angleDegrees) implements SweepShape {

        public Arc {
            Objects.requireNonNull(halfExtents, "halfExtents");
            Objects.requireNonNull(offset, "offset");
            positive(halfExtents);
            if (!(angleDegrees > 0.0D) || angleDegrees > 360.0D) {
                throw new IllegalArgumentException("SweepShape.Arc: angleDegrees must be in (0, 360], got "
                    + angleDegrees);
            }
        }
    }

    /** Single source of truth for every representation of this data (sub-02 §2). */
    Codec<SweepShape> CODEC = RecordCodecBuilder.<SweepShape>create(instance -> instance.group(
        Vec3.CODEC.fieldOf("halfExtents").forGetter(SweepShape::halfExtents),
        Vec3.CODEC.optionalFieldOf("offset", Vec3.ZERO).forGetter(SweepShape::offset),
        Codec.DOUBLE.optionalFieldOf("angleDegrees", 0.0D).forGetter(shape ->
            shape instanceof Arc arc ? arc.angleDegrees() : 0.0D)
    ).apply(instance, (extents, offset, angle) -> angle > 0.0D
        ? new Arc(extents, offset, angle)
        : new Box(extents, offset)));

    /** A readable form for logs and error messages. */
    default String asString() {
        return (this instanceof Arc arc ? "arc" : "box")
            + "(halfExtents=" + halfExtents().asString()
            + ", offset=" + offset().asString()
            + (this instanceof Arc arc ? ", angleDegrees=" + arc.angleDegrees() : "")
            + ")";
    }

    private static void positive(Vec3 extents) {
        if (!(extents.x() > 0.0D) || !(extents.y() > 0.0D) || !(extents.z() > 0.0D)) {
            throw new IllegalArgumentException("sweep halfExtents must be positive on every axis, got "
                + extents.asString());
        }
    }
}
