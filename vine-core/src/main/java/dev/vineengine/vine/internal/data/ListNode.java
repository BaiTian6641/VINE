package dev.vineengine.vine.internal.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelList;
import dev.vineengine.vine.data.VoxelType;

/**
 * Homogeneous list node (sub-03 §2). The element type is fixed by the first
 * add and every later add must match — homogeneity is what makes lists
 * self-describing on the wire (NBT lists carry one element tag id) and cheap
 * to type-check on read.
 *
 * <p>Mutations clear ancestor resolve caches via {@link VoxelDataImpl#invalidateUp}
 * — list content is part of the tree's structure.
 */
final class ListNode extends VoxelNode implements VoxelList {

    /** {@code null} while empty and unfilled — serializes as an untyped list. */
    private VoxelType elementType;
    private final ArrayList<VoxelNode> elements = new ArrayList<>();

    ListNode(TreeState tree) {
        this.tree = tree;
    }

    @Override
    VoxelType type() {
        return VoxelType.LIST;
    }

    @Override
    ListNode copyAttached(String newKey, VoxelNode newParent, TreeState targetTree) {
        ListNode copy = new ListNode(targetTree);
        copy.key = newKey;
        copy.parent = newParent;
        copy.elementType = elementType;
        for (VoxelNode element : elements) {
            copy.elements.add(element.copyAttached(null, copy, targetTree));
        }
        return copy;
    }

    /** Sets the element type up front; the wire reader knows it from the list's tag id. */
    void fixElementType(VoxelType type) {
        this.elementType = type;
    }

    // ------------------------------------------------------------------ reads

    @Override
    public VoxelType elementType() {
        return elementType;
    }

    @Override
    public int size() {
        return elements.size();
    }

    @Override
    public byte getByte(int index) {
        return ((ValueNode) element(index, VoxelType.BYTE)).byteValue();
    }

    @Override
    public short getShort(int index) {
        return ((ValueNode) element(index, VoxelType.SHORT)).shortValue();
    }

    @Override
    public int getInt(int index) {
        return ((ValueNode) element(index, VoxelType.INT)).intValue();
    }

    @Override
    public long getLong(int index) {
        return ((ValueNode) element(index, VoxelType.LONG)).longValue();
    }

    @Override
    public float getFloat(int index) {
        return ((ValueNode) element(index, VoxelType.FLOAT)).floatValue();
    }

    @Override
    public double getDouble(int index) {
        return ((ValueNode) element(index, VoxelType.DOUBLE)).doubleValue();
    }

    @Override
    public String getString(int index) {
        return ((ValueNode) element(index, VoxelType.STRING)).stringValue();
    }

    @Override
    public byte[] getByteArray(int index) {
        return ((ValueNode) element(index, VoxelType.BYTE_ARRAY)).byteArrayValue();
    }

    @Override
    public int[] getIntArray(int index) {
        return ((ValueNode) element(index, VoxelType.INT_ARRAY)).intArrayValue();
    }

    @Override
    public long[] getLongArray(int index) {
        return ((ValueNode) element(index, VoxelType.LONG_ARRAY)).longArrayValue();
    }

    @Override
    public VoxelList getList(int index) {
        return (ListNode) element(index, VoxelType.LIST);
    }

    @Override
    public VoxelData getCompound(int index) {
        return (VoxelDataImpl) element(index, VoxelType.COMPOUND);
    }

    // ----------------------------------------------------------------- writes

    @Override
    public void add(byte value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(short value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(int value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(long value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(float value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(double value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(String value) {
        addNode(ValueNode.of(Objects.requireNonNull(value, "value")));
    }

    @Override
    public void add(byte[] value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(int[] value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(long[] value) {
        addNode(ValueNode.of(value));
    }

    @Override
    public void add(VoxelList value) {
        if (!(value instanceof ListNode source)) {
            throw new IllegalArgumentException(
                "foreign VoxelList implementation " + value.getClass().getName()
                    + " — trees only accept engine-owned nodes");
        }
        addNode(source.copyAttached(null, null, tree));
    }

    @Override
    public void add(VoxelData value) {
        addNode(VoxelDataImpl.asImpl(value).copyAttached(null, null, tree));
    }

    @Override
    public void remove(int index) {
        checkWritable();
        elements.remove(requireIndex(index));
        VoxelDataImpl.invalidateUp(this);
    }

    @Override
    public VoxelList copy() {
        return copyAttached(null, null, new TreeState(tree.schemaId, tree.version, tree.readOnly));
    }

    // -------------------------------------------------------------- internals

    /**
     * Single insertion point: fixes the element type on first use, enforces
     * homogeneity after that, and takes ownership of the node. Package-visible
     * because the wire reader appends parsed elements through the same gate.
     */
    void addNode(VoxelNode node) {
        checkWritable();
        if (elementType == null) {
            elementType = node.type();
        } else if (elementType != node.type()) {
            throw new IllegalArgumentException(
                "list is homogeneous: element type fixed to " + elementType
                    + " on first insert, refusing " + node.type());
        }
        node.key = null;
        node.parent = this;
        node.tree = tree;
        elements.add(node);
        VoxelDataImpl.invalidateUp(this);
    }

    /** Element with an explicit type ask: wrong-type reads fail like the tree's, never coerce. */
    private VoxelNode element(int index, VoxelType expected) {
        VoxelNode node = elements.get(requireIndex(index));
        if (node.type() != expected) {
            throw new IllegalArgumentException(
                "list element " + index + " holds " + node.type() + ", not " + expected
                    + " — VoxelData never coerces between types");
        }
        return node;
    }

    private int requireIndex(int index) {
        if (index < 0 || index >= elements.size()) {
            throw new IndexOutOfBoundsException(
                "list index " + index + " out of bounds for size " + elements.size());
        }
        return index;
    }

    private void checkWritable() {
        if (tree.readOnly) {
            throw new IllegalStateException(
                "VoxelData list of schema " + tree.schemaId + " is read-only: blob version "
                    + tree.version + " is newer than any registered schema — the engine never destroys unknown data");
        }
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof ListNode other
            && elementType == other.elementType
            && elements.equals(other.elements);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elementType, elements);
    }

    /** Element nodes for the wire writer. */
    List<VoxelNode> elementNodes() {
        return elements;
    }
}
