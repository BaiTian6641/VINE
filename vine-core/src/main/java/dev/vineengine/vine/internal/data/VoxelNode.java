package dev.vineengine.vine.internal.data;

/**
 * Sealed node model behind the {@code VoxelData} tree (sub-03 §2): exactly a
 * scalar value, a homogeneous list, or a compound — the exhaustive switch in
 * the wire codec relies on nothing else existing.
 *
 * <p>Ownership fields ({@code tree}/{@code parent}/{@code key}) are assigned
 * by whoever attaches the node: parse builds top-down with the shared tree
 * state, mutations attach via {@link #copyAttached}. A detached node (null
 * parent) is its own tiny tree — harmless to mutate, visible to no one.
 */
abstract sealed class VoxelNode permits ValueNode, ListNode, VoxelDataImpl {

    TreeState tree;
    VoxelNode parent;
    /** Key in the owning compound; {@code null} at roots and inside lists. */
    String key;

    /** Discriminator matching {@link VoxelType}; never {@code null}. */
    abstract VoxelType type();

    /**
     * Deep copy of this subtree attached under {@code newParent} in
     * {@code targetTree}. The source subtree is never aliased — put/add
     * semantics copy, so a live tree can never appear in two places at once.
     */
    abstract VoxelNode copyAttached(String newKey, VoxelNode newParent, TreeState targetTree);
}
