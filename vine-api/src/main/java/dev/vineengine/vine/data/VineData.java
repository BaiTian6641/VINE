package dev.vineengine.vine.data;

import java.util.List;

import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.VoxelBackend;
import dev.vineengine.vine.registry.VineId;

/**
 * Static entry point for the VoxelData machinery (sub-03 §2): register
 * schemas with their fixer chains, create detached trees, and open trees at
 * attach points.
 *
 * <p>Backed by vine-core's {@code SchemaRegistry}: schema registration is
 * open during boot and throws after {@code REGISTRIES_FROZEN}; loading
 * routes older blobs through the fixer chain and opens newer-than-registered
 * blobs read-only — never destructive.
 *
 * <p>Calling any method boots the engine (same {@code ServiceLoader} seam as
 * {@code VineEngine.get()}), so a call from inside driver bootstrap is
 * rejected — drivers use {@code DriverContext} until boot completes.
 * {@link #of} additionally requires a storage driver bound by the cell driver
 * (sub-03 Stage D); headless test runtimes get an explicit failure, never a
 * guess.
 */
public final class VineData {

    private VineData() {
    }

    /**
     * Registers {@code schema} with its complete fixer chain (exactly
     * {@code schema.version() - 1} steps, v1→current).
     *
     * @throws IllegalArgumentException if the chain is incomplete
     * @throws IllegalStateException if the id is already registered or
     *         registration is frozen
     */
    public static void registerSchema(VoxelSchema schema, List<VoxelDataFixer> fixers) {
        backend().registerSchema(schema, fixers);
    }

    /**
     * Creates an empty writable tree at the schema's current version.
     *
     * @throws IllegalArgumentException if the schema is not registered
     */
    public static VoxelData create(VineId schemaId) {
        return backend().create(schemaId);
    }

    /**
     * Opens (or creates) the tree of {@code schemaId} at {@code target} —
     * attach-point access; mutations through the returned tree are the
     * consumer's portable state.
     *
     * @throws IllegalStateException if no storage driver is bound (headless
     *         runtime or pre-Stage-D driver)
     */
    public static VoxelData of(VoxelTarget target, VineId schemaId) {
        return backend().open(target, schemaId);
    }

    private static VoxelBackend backend() {
        if (EngineAccess.get() instanceof VoxelBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "vine-core engine does not provide voxel-data services — mismatched vine-api/vine-core jars");
    }
}
