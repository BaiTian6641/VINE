package dev.vineengine.vine.world;

/**
 * An engine position in continuous space (sub-07 Stage C): interaction hit points
 * today, entity and particle positions later. Double precision because that is what
 * a cell's own interaction data carries, so no driver has to round before handing it
 * over.
 *
 * @param x the X coordinate
 * @param y the Y coordinate
 * @param z the Z coordinate
 */
public record Vec3(double x, double y, double z) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final com.mojang.serialization.Codec<Vec3> CODEC =
        com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
            com.mojang.serialization.Codec.DOUBLE.fieldOf("x").forGetter(Vec3::x),
            com.mojang.serialization.Codec.DOUBLE.fieldOf("y").forGetter(Vec3::y),
            com.mojang.serialization.Codec.DOUBLE.fieldOf("z").forGetter(Vec3::z)
        ).apply(instance, Vec3::new));

    /** The origin. */
    public static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    public static Vec3 of(double x, double y, double z) {
        return new Vec3(x, y, z);
    }

    /** This point translated by the given deltas. */
    public Vec3 offset(double dx, double dy, double dz) {
        return new Vec3(x + dx, y + dy, z + dz);
    }

    /** The compact form trace lines use: {@code x,y,z} with three decimals. */
    public String asString() {
        return String.format(java.util.Locale.ROOT, "%.3f,%.3f,%.3f", x, y, z);
    }
}
