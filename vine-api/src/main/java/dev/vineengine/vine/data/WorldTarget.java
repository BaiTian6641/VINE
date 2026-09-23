package dev.vineengine.vine.data;

/**
 * One world attach point: data lives in the world's SavedData under
 * {@code vine_<ns>} (sub-03 §2) — the namespace comes from the schema id,
 * so worlds hold one store per consuming namespace.
 *
 * <p>Stage C seam: the payload is the per-cell server-level object until
 * sub-13 ships the engine world facade; drivers interpret, signatures never
 * name a game type.
 */
public record WorldTarget(Object level) implements VoxelTarget {

    public WorldTarget {
        java.util.Objects.requireNonNull(level, "level");
    }
}
