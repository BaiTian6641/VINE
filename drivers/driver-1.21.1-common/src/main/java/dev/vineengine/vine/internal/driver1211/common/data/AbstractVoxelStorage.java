package dev.vineengine.vine.internal.driver1211.common.data;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.data.BlockEntityTarget;
import dev.vineengine.vine.data.EntityTarget;
import dev.vineengine.vine.data.ItemStackTarget;
import dev.vineengine.vine.data.PlayerTarget;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.internal.data.VoxelBlobCodec;
import dev.vineengine.vine.internal.data.VoxelStorageBinding.EngineVoxels;
import dev.vineengine.vine.internal.spi.VoxelStorageDriver;
import dev.vineengine.vine.registry.VineId;

/**
 * The storage driver shared by both 1.21.1 cells (sub-03 Stages D + E): it maps
 * every reachable {@link VoxelTarget} to the cell's native holder and keeps the
 * blob opaque — every read goes through the engine's load handle (fix-on-load,
 * read-only guard) and every write through its save handle.
 *
 * <p>Holder shapes per cell (sub-03 §2): item stacks = the engine's
 * {@code vine:voxel_data} data component (portable, carried by vanilla sync);
 * block entities, entities and players = the cell's data-attachment API under
 * the same engine key (NeoForge {@code AttachmentType}, Fabric
 * {@code AttachmentRegistry}) — both loaders persist attachments in the holder's
 * own save data, which is what makes a tree survive a save/reload.
 * {@code WorldTarget} stays with sub-13's world surface.
 *
 * <p>Native-mapped fields ({@code FieldStrategy.Native}) are copied at the edges:
 * the native component's value is read into the tree on {@link #open} and written
 * back on {@link #flushDirty}, so vanilla systems and engine code agree without
 * either side reading the other's payload.
 *
 * <p>Native-mapped fields are an item-stack concept in this stage
 * ({@code FieldStrategy.Native} mirrors vanilla components such as
 * {@code minecraft:damage}); a holder kind that has no component view reports
 * "unknown component" and the mapping is skipped with a warning, never guessed.
 */
public abstract class AbstractVoxelStorage implements VoxelStorageDriver {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractVoxelStorage.class);

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
        Object stack = holderOf(target);
        // Holder payloads are bundles: several schemas can share one holder (an
        // entity carrying engine data *and* a capability state), so a schema's
        // tree is one entry, never the whole payload.
        byte[] slice = bundleOf(stack).get(schemaId);
        VoxelData tree = slice == null ? voxels().create(schemaId) : voxels().load(slice);
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
        Object stack = holderOf(target);
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
        // Read-modify-write of the bundle keeps the holder's other schemas intact —
        // the failure this shape exists to prevent.
        java.util.Map<VineId, byte[]> bundle = new java.util.LinkedHashMap<>(bundleOf(stack));
        bundle.put(schemaId, voxels().save(tree));
        storeBlob(stack, VoxelBlobCodec.saveBundle(bundle));
    }

    /** The holder's decoded payload bundle (empty when the holder stores nothing yet). */
    private java.util.Map<VineId, byte[]> bundleOf(Object holder) {
        byte[] payload = storedBlob(holder);
        return payload == null ? java.util.Map.of()
            : VoxelBlobCodec.loadBundle(payload, VineId.of("vine", "legacy_single"));
    }

    /**
     * The native holder a target names. Every holder kind the current wave
     * supports maps here; anything else fails loudly instead of silently
     * attaching nowhere.
     */
    private static Object holderOf(VoxelTarget target) {
        if (target instanceof ItemStackTarget itemStack) {
            return itemStack.stack();
        }
        if (target instanceof BlockEntityTarget blockEntity) {
            return blockEntity.blockEntity();
        }
        if (target instanceof EntityTarget entity) {
            return entity.entity();
        }
        if (target instanceof PlayerTarget player) {
            return player.player();
        }
        throw new UnsupportedOperationException(
            "world attach (" + target.getClass().getSimpleName() + ") lands with sub-13's world surface");
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
