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
