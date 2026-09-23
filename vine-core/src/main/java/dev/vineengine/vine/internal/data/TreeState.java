package dev.vineengine.vine.internal.data;

import java.util.HashMap;

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
}
