package dev.vineengine.vine.internal.data;

import java.util.HashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSyncListener;
import dev.vineengine.vine.registry.VineId;

/**
 * State shared by every node of one {@code VoxelData} tree (sub-03 §2
 * internals): schema identity, engine schema version, the read-only guard,
 * and the per-tree path-segment interner.
 *
 * <p><b>Invariants:</b> exactly one instance per attached tree; nodes joined
 * by {@code copyAttached} adopt the target tree's state, so a node's state
 * always matches its parent's. Detached trees (fresh roots, missing-path
 * {@code getCompound}/{@code getList} results, deep copies) own a private
 * state — mutating them can never reach another tree.
 *
 * <p>The interner canonicalizes child-key strings per tree so repeated puts
 * of the same key share one {@code String} instance and map lookups hit the
 * reference-equality fast path. It is unbounded by design: keys come from
 * schema-shaped paths, not adversarial input.
 */
final class TreeState {

    final VineId schemaId;
    /** Engine schema version this tree currently holds; bumped by fix-on-load. */
    int version;
    /**
     * True when the blob's schema version is newer than anything registered —
     * the tree opens read-only so unknown data is never destroyed (sub-03 §2).
     */
    boolean readOnly;

    /**
     * Registered sync listeners (sub-03 Stage C registration, Stage E dispatch).
     * Copy-on-write: registration is boot-time-rare, dispatch iterates on tick
     * threads.
     */
    private final CopyOnWriteArrayList<VoxelSyncListener> syncListeners = new CopyOnWriteArrayList<>();

    /**
     * Paths touched since the last drain, with ancestor coarsening applied by
     * {@link #markDirty}. This is what a driver's {@code flushDirty} persists and
     * what a sync pass transmits — never the whole tree, which is reserved for
     * initial send and late-join.
     */
    private final java.util.LinkedHashSet<String> dirtyPaths = new java.util.LinkedHashSet<>();

    private final HashMap<String, String> segmentInterner = new HashMap<>();

    TreeState(VineId schemaId, int version) {
        this(schemaId, version, false);
    }

    TreeState(VineId schemaId, int version, boolean readOnly) {
        this.schemaId = schemaId;
        this.version = version;
        this.readOnly = readOnly;
    }

    /** Canonical instance for one child-key segment, allocating only on first sight. */
    String intern(String segment) {
        return segmentInterner.computeIfAbsent(segment, s -> s);
    }

    void addListener(VoxelSyncListener listener) {
        syncListeners.addIfAbsent(listener);
    }

    /**
     * Records one mutation (sub-03 Stage E): the mutated path plus its
     * ancestors, accumulated for the next flush and dispatched to listeners —
     * per mutation, with that mutation's set, exactly as
     * {@link VoxelSyncListener#onChange} documents.
     */
    void markDirty(VoxelData data, String path) {
        java.util.Set<String> touched = new java.util.LinkedHashSet<>(2);
        String at = path;
        while (!at.isEmpty()) {
            touched.add(at);
            dirtyPaths.add(at);
            int dot = at.lastIndexOf('.');
            at = dot < 0 ? "" : at.substring(0, dot);
        }
        if (syncListeners.isEmpty()) {
            return;
        }
        java.util.Set<String> immutable = java.util.Collections.unmodifiableSet(touched);
        for (VoxelSyncListener listener : syncListeners) {
            try {
                listener.onChange(data, immutable);
            } catch (RuntimeException listenerFailure) {
                // A listener is consumer (or sync-transport) code: one failing
                // observer must not corrupt the write that triggered it.
                System.getLogger("vine.voxel").log(System.Logger.Level.WARNING,
                    "[VINE] voxel change listener failed on path " + path + ": " + listenerFailure,
                    listenerFailure);
            }
        }
    }

    /** The dirty paths accumulated since the last drain (empty when nothing mutated). */
    synchronized java.util.Set<String> dirtySnapshot() {
        return java.util.Set.copyOf(dirtyPaths);
    }

    /** Takes the accumulated dirty paths, clearing them for the next mutation window. */
    synchronized java.util.Set<String> drainDirty() {
        java.util.Set<String> drained = java.util.Set.copyOf(dirtyPaths);
        dirtyPaths.clear();
        return drained;
    }
}
