package dev.vineengine.vine.world;

/**
 * An engine block position (sub-07 Stage B): three integers, the addressing every
 * supported cell already uses. Engine-owned on purpose — a consumer never names a
 * loader or game class, and the engine's own types are the only ones that cross
 * the API boundary.
 *
 * <p><b>Invariants:</b> immutable value; equality and ordering are
 * identity-of-coordinates. No range validation happens here: a position outside a
 * cell's build height is the world's business, and the world reports it as an
 * absent state rather than as an exception.
 *
 * @param x block X (world coordinates, not chunk-relative)
 * @param y block Y; a cell's build height is the world's business
 * @param z block Z (world coordinates, not chunk-relative)
 */
public record BlockPos(int x, int y, int z) {

    /** The origin — the position probe scenarios start from. */
    public static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    /** The canonical constructor by explicit coordinates. */
    public static BlockPos of(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** This position translated by the given deltas. */
    public BlockPos offset(int dx, int dy, int dz) {
        return new BlockPos(x + dx, y + dy, z + dz);
    }

    /** The compact form every trace line uses: {@code x,y,z}. */
    public String asString() {
        return x + "," + y + "," + z;
    }
}
