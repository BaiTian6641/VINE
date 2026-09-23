package dev.vineengine.vine.internal.data;

/**
 * Zero-copy read view over a {@link VoxelData} tree (sub-03 §2): the perf
 * surface for tick-hot readers — one small wrapper, no traversal, no copies.
 *
 * <p>The view is <em>live</em>: it reflects mutations made through the
 * owning tree, by design (plan §9 "zero-copy views where possible"). It
 * exposes no mutators itself; compound children surface as nested views.
 * Lists still come back as {@link VoxelList} (live, shared for zero-copy) —
 * the tree's list surface is the same object on both sides of the view.
 */
public interface VoxelView {

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

    VoxelList getList(String path);

    /** Nested zero-copy view of the compound at {@code path}. */
    VoxelView getCompound(String path);

    int schemaVersion();
}
