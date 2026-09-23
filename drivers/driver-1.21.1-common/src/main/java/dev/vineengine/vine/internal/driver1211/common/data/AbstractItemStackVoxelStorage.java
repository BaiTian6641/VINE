package dev.vineengine.vine.internal.driver1211.common.data;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.data.ItemStackTarget;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.internal.data.VoxelStorageBinding.EngineVoxels;
import dev.vineengine.vine.internal.spi.VoxelStorageDriver;
import dev.vineengine.vine.registry.VineId;

/**
 * Item-stack attach point for both 1.21.1 cells (sub-03 Stage D): the tree's
 * blob lives in the engine's {@code vine:voxel_data} data component, so vanilla
 * save/load and item sync carry it unchanged. Blobs are opaque — every read goes
 * through the engine's load handle (fix-on-load, read-only guard) and every write
 * through its save handle.
 *
 * <p>Native-mapped fields ({@code FieldStrategy.Native}) are copied at the edges:
 * the native component's value is read into the tree on {@link #open} and written
 * back on {@link #flushDirty}, so vanilla systems and engine code agree without
 * either side reading the other's payload.
 *
 * <p>Block-entity, entity and player attach points (BE save-tag sub-compound, NF
 * data attachments / Fabric custom data) need loader hooks and land with the
 * Mixin wave (sub-18 Stage E) — the SPI shape does not change when they do.
 */
public abstract class AbstractItemStackVoxelStorage implements VoxelStorageDriver {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractItemStackVoxelStorage.class);

    private volatile EngineVoxels voxels;
    private final Map<Object, VoxelData> openTrees = new IdentityHashMap<>();

    /**
     * Installs the engine's blob handle — the value {@code VoxelStorageBinding.bind}
     * returns, so binding order is: construct, bind, then hand the handle back here.
     */
    public void engine(EngineVoxels engineVoxels) {
        this.voxels = engineVoxels;
    }

    protected EngineVoxels voxels() {
        EngineVoxels current = voxels;
        if (current == null) {
            throw new IllegalStateException("voxel storage used before the engine handle was installed");
        }
        return current;
    }

    @Override
    public VoxelData open(VoxelTarget target, VineId schemaId) {
        Object stack = stackOf(target);
        byte[] blob = storedBlob(stack);
        VoxelData tree = blob == null ? voxels().create(schemaId) : voxels().load(blob);
        voxels().nativeFields(schemaId).forEach((path, componentId) -> {
            int nativeValue = readNativeInt(stack, componentId);
            if (nativeValue != Integer.MIN_VALUE) {
                tree.put(path, nativeValue);
            } else {
                LOG.warn("[VINE] voxeldata: unknown native component '{}' for path '{}' — skipped",
                    componentId, path);
            }
        });
        synchronized (openTrees) {
            openTrees.put(stack, tree);
        }
        return tree;
    }

    @Override
    public void flushDirty(VoxelTarget target, VineId schemaId, Set<String> dirtyPaths) {
        Object stack = stackOf(target);
        VoxelData tree;
        synchronized (openTrees) {
            tree = openTrees.get(stack);
        }
        if (tree == null) {
            LOG.warn("[VINE] voxeldata: flush for a target that was never opened — ignored");
            return;
        }
        voxels().nativeFields(schemaId).forEach((path, componentId) -> {
            if (writeNativeInt(stack, componentId, tree.getInt(path)) == false) {
                LOG.warn("[VINE] voxeldata: unknown native component '{}' for path '{}' — skipped",
                    componentId, path);
            }
        });
        storeBlob(stack, voxels().save(tree));
    }

    private static Object stackOf(VoxelTarget target) {
        if (target instanceof ItemStackTarget itemStack) {
            return itemStack.stack();
        }
        throw new UnsupportedOperationException(
            "this cell's voxel storage currently attaches item stacks only; " + target.getClass().getSimpleName()
                + " arrives with the Mixin wave (sub-18 Stage E)");
    }

    /** The blob currently stored on {@code stack}, or null. */
    protected abstract byte[] storedBlob(Object stack);

    /** Stores {@code blob} on {@code stack} (replacing any previous payload). */
    protected abstract void storeBlob(Object stack, byte[] blob);

    /** Reads a native int component; {@code Integer.MIN_VALUE} when the id is unknown here. */
    protected abstract int readNativeInt(Object stack, String componentId);

    /** Writes a native int component; false when the id is unknown here. */
    protected abstract boolean writeNativeInt(Object stack, String componentId, int value);
}
