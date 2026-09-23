package dev.vineengine.vine.internal.data;

import dev.vineengine.vine.data.VoxelList;
import dev.vineengine.vine.data.VoxelType;
import dev.vineengine.vine.data.VoxelView;

/**
 * Zero-copy live read view (sub-03 §2): wraps one compound node, no traversal
 * on creation, no defensive copies on read. Delegates straight to
 * {@link VoxelDataImpl} reads, so mutations through the owning tree are
 * visible immediately — that liveness is the acceptance contract of
 * {@code snapshot()}, not an accident.
 */
final class VoxelViewImpl implements VoxelView {

    private final VoxelDataImpl node;

    VoxelViewImpl(VoxelDataImpl node) {
        this.node = node;
    }

    @Override
    public boolean contains(String path) {
        return node.contains(path);
    }

    @Override
    public VoxelType typeOf(String path) {
        return node.typeOf(path);
    }

    @Override
    public byte getByte(String path) {
        return node.getByte(path);
    }

    @Override
    public short getShort(String path) {
        return node.getShort(path);
    }

    @Override
    public int getInt(String path) {
        return node.getInt(path);
    }

    @Override
    public long getLong(String path) {
        return node.getLong(path);
    }

    @Override
    public float getFloat(String path) {
        return node.getFloat(path);
    }

    @Override
    public double getDouble(String path) {
        return node.getDouble(path);
    }

    @Override
    public String getString(String path) {
        return node.getString(path);
    }

    @Override
    public byte[] getByteArray(String path) {
        return node.getByteArray(path);
    }

    @Override
    public int[] getIntArray(String path) {
        return node.getIntArray(path);
    }

    @Override
    public long[] getLongArray(String path) {
        return node.getLongArray(path);
    }

    @Override
    public VoxelList getList(String path) {
        return node.getList(path);
    }

    @Override
    public VoxelView getCompound(String path) {
        return new VoxelViewImpl(VoxelDataImpl.asImpl(node.getCompound(path)));
    }

    @Override
    public int schemaVersion() {
        return node.schemaVersion();
    }
}
