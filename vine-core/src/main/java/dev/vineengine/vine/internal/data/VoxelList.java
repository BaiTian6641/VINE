package dev.vineengine.vine.internal.data;

/**
 * A homogeneous ordered list node of the {@code VoxelData} tree (sub-03 §2).
 *
 * <p><b>Invariant — homogeneity:</b> the element type is fixed by the first
 * {@code add} and every later element must match; a mismatched add throws
 * {@link IllegalArgumentException} instead of widening or coercing. An empty
 * list that has never held an element reports a {@code null} element type and
 * serializes as an untyped NBT list.
 *
 * <p>Reads follow the tree's conventions: index out of bounds throws
 * {@link IndexOutOfBoundsException}; {@code getCompound}/{@code getList}
 * return <em>live</em> elements (mutations propagate into the tree, same as
 * {@link VoxelData#getCompound}); array gets return the internal array for
 * zero-copy reads — treat it as read-only. A list obtained for a missing path
 * is detached: mutable, but its changes reach no tree.
 */
public interface VoxelList {

    /** Element type fixed on first insert, or {@code null} while empty and unfilled. */
    VoxelType elementType();

    int size();

    byte getByte(int index);

    short getShort(int index);

    int getInt(int index);

    long getLong(int index);

    float getFloat(int index);

    double getDouble(int index);

    String getString(int index);

    byte[] getByteArray(int index);

    int[] getIntArray(int index);

    long[] getLongArray(int index);

    /** Live child list when the element type is {@code LIST}. */
    VoxelList getList(int index);

    /** Live child compound when the element type is {@code COMPOUND}. */
    VoxelData getCompound(int index);

    /** Appends one element; the first add fixes the element type for this list. */
    void add(byte value);

    void add(short value);

    void add(int value);

    void add(long value);

    void add(float value);

    void add(double value);

    void add(String value);

    void add(byte[] value);

    void add(int[] value);

    void add(long[] value);

    /** Appends a deep copy of {@code value}; the source list is never aliased. */
    void add(VoxelList value);

    /** Appends a deep copy of {@code value}; the source compound is never aliased. */
    void add(VoxelData value);

    /** Removes the element at {@code index}. */
    void remove(int index);

    /** Detached deep copy; later edits to either side never affect the other. */
    VoxelList copy();
}
