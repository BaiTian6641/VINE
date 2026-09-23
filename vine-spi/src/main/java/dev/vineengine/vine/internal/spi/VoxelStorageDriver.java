package dev.vineengine.vine.internal.spi;

import java.util.Set;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.registry.VineId;

/**
 * The driver-side storage SPI (sub-03 §2 "Driver contract"): how the engine
 * reaches a cell's attach points. One per cell, implemented only by VINE's
 * own driver jars, bound once during driver bootstrap via vine-core's
 * {@code VoxelStorageBinding} — which hands back the engine's blob
 * load/save handle so drivers never touch the schema registry directly.
 *
 * <p><b>Attach points per cell (sub-03 §2):</b> item stacks = component
 * get/set on the engine's {@code vine:voxel_data} component; block entities =
 * reserved {@code vine} sub-compound in the BE save tag; entities + players =
 * NF data attachments / Fabric custom-data under the {@code vine} key;
 * world = SavedData {@code vine_<ns>}. Registration timing (1.21.1 NF
 * {@code DeferredRegister} at {@code REGISTRIES_OPEN}; 1.21.1-Fabric
 * {@code Registry.register} in mod init; 26.x absorbs drift with sub-19) is
 * the driver's to absorb; every cell asserts the {@code hasDataComponents}
 * probe at boot — absence is a clean boot failure.
 *
 * <p><b>Invariants:</b> {@link #open} returns a live tree whose mutations the
 * driver later receives via {@link #flushDirty} (path-granular dirty set —
 * sub-03 Stage E activates tracking; whole-tree only for initial/late-join);
 * blobs are opaque engine-headered bytes — the driver stores them unchanged
 * and routes every read through the engine's load handle (fix-on-load and
 * the read-only newer-version guard are engine-side, never driver-side).
 */
public interface VoxelStorageDriver {

    /**
     * Opens the stored tree for {@code (target, schemaId)} — loading the
     * stored blob through the engine's load handle when present, creating a
     * fresh tree otherwise — and attaches it at the target's storage.
     */
    VoxelData open(VoxelTarget target, VineId schemaId);

    /**
     * Persists the dirty slice of the tree the driver opened for
     * {@code (target, schemaId)}: re-encode via the engine's save handle and
     * store at the attach point.
     */
    void flushDirty(VoxelTarget target, VineId schemaId, Set<String> dirtyPaths);
}
