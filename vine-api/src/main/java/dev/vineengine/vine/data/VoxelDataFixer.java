package dev.vineengine.vine.data;

/**
 * One engine-side datafix step: upgrades a tree from {@code fromVersion} to
 * {@code fromVersion + 1} (sub-03 §2). Deliberately a plain function —
 * <em>not</em> Mojang's {@code DataFixer}/{@code Schema} machinery — so
 * consumer migrations stay decoupled from Mojang's data-version churn and
 * portable across every cell unchanged.
 *
 * <p>Called lazily at load, in chain order. May mutate and return the given
 * tree or build and return a fresh one; returning {@code null} or a tree of a
 * different schema fails the load explicitly.
 */
public interface VoxelDataFixer {

    /** @param fromVersion version the tree currently holds on entry */
    VoxelData fix(VoxelData data, int fromVersion);
}
