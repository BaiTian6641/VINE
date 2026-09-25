package dev.vineengine.vine.entity;

import java.util.Map;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * One part of a multipart entity as data (sub-08 Stage A): where the part sits on
 * its animation bone, how big its collider is, how it trades damage by type, and
 * the two thresholds that make a Monster-Hunter-shaped fight possible — flinch
 * (the part's damage can interrupt a brain node) and break (cumulative damage past
 * it changes the part's state and its multipliers).
 *
 * <p>The part is named by the engine, never by the cell's entity class, and it is
 * parented to a <em>bone name</em> from the animation asset rather than to a
 * native model part: the pose evaluator (sub-09) is what turns that name into
 * geometry, so the same descriptor means the same thing on every cell.
 *
 * <p><b>Invariants:</b> the name matches the engine's part-name grammar
 * ({@code [a-z0-9_]+} — the same grammar the blackboard and {@code VoxelData}
 * paths use, because a part's wound state is stored under its name) and is unique
 * inside its entity; size components are positive; every multiplier is
 * non-negative (zero means "immune to that damage type", which is a meaningful
 * declaration, not a mistake); thresholds are non-negative. Immutable.
 *
 * @param name              the part's engine name; also the key of its persistent state
 * @param parentBone        the animation bone the part is attached to (sub-09 names it)
 * @param size              the part collider's full size, in blocks, in bone-local axes
 * @param offset            the collider's centre relative to the bone's pivot, in blocks
 * @param damageMultipliers per-damage-type hitzone multiplier; a type that is absent
 *                          is 1.0 (neutral), and 0.0 is explicit immunity
 * @param flinchThreshold   cumulative damage on this part that may interrupt the
 *                          entity's current brain node; {@code 0} disables flinching
 * @param breakThreshold    cumulative damage on this part that flips it to broken;
 *                          {@code 0} disables breaking
 */
public record PartDescriptor(String name, String parentBone, Vec3 size, Vec3 offset,
        Map<VineId, Float> damageMultipliers, float flinchThreshold, float breakThreshold) {

    /** The engine's part-name grammar — also the key grammar of the state it stores. */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_]+");

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<PartDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.STRING.fieldOf("name").forGetter(PartDescriptor::name),
        Codec.STRING.fieldOf("parentBone").forGetter(PartDescriptor::parentBone),
        Vec3.CODEC.fieldOf("size").forGetter(PartDescriptor::size),
        Vec3.CODEC.optionalFieldOf("offset", Vec3.ZERO).forGetter(PartDescriptor::offset),
        Codec.unboundedMap(VineId.CODEC, Codec.FLOAT).optionalFieldOf("damageMultipliers", Map.of())
            .forGetter(PartDescriptor::damageMultipliers),
        Codec.FLOAT.optionalFieldOf("flinchThreshold", 0.0F).forGetter(PartDescriptor::flinchThreshold),
        Codec.FLOAT.optionalFieldOf("breakThreshold", 0.0F).forGetter(PartDescriptor::breakThreshold)
    ).apply(instance, PartDescriptor::new));

    /** A neutral part: a collider on a bone, no multipliers, no thresholds. */
    public static PartDescriptor of(String name, String parentBone, Vec3 size) {
        return new PartDescriptor(name, parentBone, size, Vec3.ZERO, Map.of(), 0.0F, 0.0F);
    }

    public PartDescriptor {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(parentBone, "parentBone");
        Objects.requireNonNull(size, "size");
        Objects.requireNonNull(offset, "offset");
        damageMultipliers = Map.copyOf(Objects.requireNonNull(damageMultipliers, "damageMultipliers"));
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("PartDescriptor.name must match [a-z0-9_]+ (it is also the key of the"
                + " part's stored state): " + name);
        }
        if (parentBone.isBlank()) {
            throw new IllegalArgumentException("PartDescriptor " + name + ": parentBone must name the animation bone"
                + " the part rides");
        }
        if (!(size.x() > 0.0D) || !(size.y() > 0.0D) || !(size.z() > 0.0D)) {
            throw new IllegalArgumentException("PartDescriptor " + name + ": size must be positive on every axis, got "
                + size.asString());
        }
        for (Map.Entry<VineId, Float> entry : damageMultipliers.entrySet()) {
            Objects.requireNonNull(entry.getKey(), "damageMultipliers key");
            Float multiplier = Objects.requireNonNull(entry.getValue(), "damageMultipliers value");
            if (!(multiplier >= 0.0F)) {
                throw new IllegalArgumentException("PartDescriptor " + name + ": multiplier for " + entry.getKey()
                    + " must be non-negative (0 means immune), got " + multiplier);
            }
        }
        if (!(flinchThreshold >= 0.0F)) {
            throw new IllegalArgumentException("PartDescriptor " + name + ": flinchThreshold must be non-negative, got "
                + flinchThreshold);
        }
        if (!(breakThreshold >= 0.0F)) {
            throw new IllegalArgumentException("PartDescriptor " + name + ": breakThreshold must be non-negative, got "
                + breakThreshold);
        }
    }
}
