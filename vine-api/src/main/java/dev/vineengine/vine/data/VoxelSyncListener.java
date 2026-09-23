package dev.vineengine.vine.data;

import java.util.Set;

/**
 * Observer of tree mutations for sync purposes (sub-03 §2): receives the
 * dirty path-set a mutation produced — the mutated path plus its ancestors
 * (path-granular dirty sets with ancestor coarsening; whole-tree only for
 * initial/late-join, carried by sub-05 transport).
 *
 * <p><b>Stage seam:</b> registration is live from sub-03 Stage C; dispatch
 * activates with Stage E (sync-delta) together with client-visible path
 * filtering — listeners are dormant until then by design (Minimal Footprint).
 */
public interface VoxelSyncListener {

    /**
     * @param data the tree that mutated (live; read what you need, never mutate here)
     * @param dirtyPaths the path plus ancestors marked dirty by one mutation
     */
    void onChange(VoxelData data, Set<String> dirtyPaths);
}
