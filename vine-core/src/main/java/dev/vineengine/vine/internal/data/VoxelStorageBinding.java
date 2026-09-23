package dev.vineengine.vine.internal.data;

import java.util.Objects;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

import dev.vineengine.vine.internal.spi.VoxelStorageDriver;

/**
 * The core-side binding point for the process's single
 * {@link VoxelStorageDriver} (sub-03 §2). A driver binds once, during
 * bootstrap; {@code VineData#of} routes here. Mirror of the net seam's
 * exactly-one-bind rule: a second bind is a conflicting-driver bug.
 *
 * <p>Binding hands the driver the engine's {@link EngineVoxels} handle —
 * blob load/save against the engine's schema registry — so fix-on-load and
 * the read-only newer-version guard stay engine-side on every cell. The
 * engine installs its registry at construction, before any driver can bind
 * (same ordering rule as the net seam's inbound sink).
 */
public final class VoxelStorageBinding {

    private static volatile VoxelStorageDriver driver;
    private static volatile SchemaRegistry engineRegistry;

    private VoxelStorageBinding() {
    }

    /**
     * Installs the engine's schema registry. Called once by vine-core's engine
     * object at construction — before any driver can bind.
     */
    public static void engineRegistry(SchemaRegistry registry) {
        if (engineRegistry != null) {
            throw new IllegalStateException("engine schema registry already installed");
        }
        engineRegistry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * Binds the process storage driver and returns the engine's blob handle.
     *
     * @throws IllegalStateException on a second bind, or if the engine has not
     *         installed its schema registry (driver binding before engine
     *         boot — a driver bug, reported explicitly)
     */
    public static synchronized EngineVoxels bind(VoxelStorageDriver candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (driver != null) {
            throw new IllegalStateException("VoxelStorageDriver already bound: " + driver.getClass().getName()
                + " (conflicting second: " + candidate.getClass().getName() + ")");
        }
        SchemaRegistry registry = engineRegistry;
        if (registry == null) {
            throw new IllegalStateException(
                "driver bound a VoxelStorageDriver before the engine installed its schema registry");
        }
        driver = candidate;
        return new EngineVoxels(registry);
    }

    /** The bound driver, or {@code null} when headless / pre-Stage-D — callers fail explicitly. */
    public static VoxelStorageDriver bound() {
        return driver;
    }

    /**
     * The engine's blob handle for drivers: load routes a stored blob through
     * the engine schema registry (fix-on-load, read-only guard); save stamps
     * the engine header. This is the only load/save path drivers may use —
     * it is what keeps datafix semantics identical across cells.
     */
    public static final class EngineVoxels {

        private final SchemaRegistry schemas;

        private EngineVoxels(SchemaRegistry schemas) {
            this.schemas = schemas;
        }

        /** See {@link VoxelBlobCodec#load}. */
        public VoxelData load(byte[] blob) {
            return VoxelBlobCodec.load(blob, schemas);
        }

        /** See {@link VoxelBlobCodec#save}. */
        public byte[] save(VoxelData data) {
            return VoxelBlobCodec.save(data);
        }

        /** Fresh tree for {@code schemaId} — the engine-side create drivers use on attach. */
        public VoxelData create(VineId schemaId) {
            return schemas.create(schemaId);
        }

        /** Native-mapped paths for {@code schemaId} ({@code path -> component id}). */
        public java.util.Map<String, String> nativeFields(VineId schemaId) {
            return NativeFields.fieldsFor(schemaId);
        }
    }
}
