package dev.vineengine.vine.data;

/**
 * The engine-owned, self-describing data tree — the single portable carrier
 * for consumer state on every cell (sub-03 §2, README shared vocabulary).
 * Portable-strategy fields ride one engine-owned Data Component
 * ({@code vine:voxel_data}) with identical semantics on every cell;
 * native-strategy fields map per {@link FieldStrategy}.
 *
 * <p><b>Paths:</b> dot-paths ({@code "stats.mana"}) address nested compound
 * keys; every segment must be non-empty. <b>Missing vs wrong:</b> a missing
 * path reads as the type's zero value ({@code 0}/{@code ""}/empty); a path
 * holding a different type throws {@link IllegalArgumentException} — the tree
 * is self-describing and the engine never guesses a coercion.
 *
 * <p><b>Liveness:</b> {@link #getCompound}/{@link #getList} on an existing
 * path return live nodes — mutations through them propagate into this tree.
 * On a <em>missing</em> path they return a detached empty node (mutable, but
 * reaching no tree); create the path explicitly to attach. Intermediate
 * compounds are auto-created by {@code put}; an existing non-compound in the
 * way throws instead of being silently destroyed.
 *
 * <p><b>Zero-copy:</b> array gets return the internal array; {@link #snapshot}
 * is a live read view over this same tree. Treat returned arrays as
 * read-only. {@code put} of arrays/compounds/lists defensively copies in.
 */
public interface VoxelData {

    boolean contains(String path);

    /** Type at {@code path}, or {@code null} when absent. */
    VoxelType typeOf(String path);

    byte getByte(String path);

    short getShort(String path);

    int getInt(String path);

    long getLong(String path);

    float getFloat(String path);

    double getDouble(String path);

    String getString(String path);

    byte[] getByteArray(String path);

    int[] getIntArray(String path);

    long[] getLongArray(String path);

    /** Live list at {@code path}; detached empty when absent. */
    VoxelList getList(String path);

    /** Live compound at {@code path}; detached empty when absent. */
    VoxelData getCompound(String path);

    /** Writes a value, auto-creating missing intermediate compounds. */
    void put(String path, byte value);

    void put(String path, short value);

    void put(String path, int value);

    void put(String path, long value);

    void put(String path, float value);

    void put(String path, double value);

    void put(String path, String value);

    void put(String path, byte[] value);

    void put(String path, int[] value);

    void put(String path, long[] value);

    /** Attaches a deep copy of {@code value} at {@code path}. */
    void put(String path, VoxelList value);

    /** Attaches a deep copy of {@code value} at {@code path}. */
    void put(String path, VoxelData value);

    /** Removes {@code path}; removing an absent path is a no-op. */
    void remove(String path);

    /** Zero-copy live read view; reflects later mutations of this tree. */
    VoxelView snapshot();

    /** Engine schema version this tree currently holds (fix-on-load bumps it). */
    int schemaVersion();

    /**
     * Registers {@code listener} for dirty-path change dispatch. Registration
     * is accepted from this stage; dispatch activates with sub-03 Stage E
     * (sync-delta: mutation marks the path plus its ancestors, deltas ride
     * sub-05 transport) — listeners are dormant until then by design.
     */
    void addChangeListener(VoxelSyncListener listener);

    /** Detached deep copy; later edits to either side never affect the other. */
    VoxelData copy();
}
