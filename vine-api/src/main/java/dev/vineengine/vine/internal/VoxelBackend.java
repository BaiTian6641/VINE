package dev.vineengine.vine.internal;

import java.util.List;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelDataFixer;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.registry.VineId;

/**
 * Internal bridge from {@code VineData} (vine-api) to vine-core's schema
 * registry and storage routing. NOT public API — implemented once by
 * vine-core's engine object; never implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess} rather than its own ServiceLoader
 * seam, so the engine's exactly-one-provider rule, boot ordering, and cached
 * boot failure apply to data calls unchanged (same pattern as
 * {@link RegistryBackend}).
 */
public interface VoxelBackend {

    /** See {@code VineData#registerSchema}. */
    void registerSchema(VoxelSchema schema, List<VoxelDataFixer> fixers);

    /** See {@code VineData#create}. */
    VoxelData create(VineId schemaId);

    /** See {@code VineData#of} — routes to the bound {@code VoxelStorageDriver}. */
    VoxelData open(VoxelTarget target, VineId schemaId);

    /** See {@code VineData#encode} — blob serialization (fixtures, transport). */
    byte[] encode(VoxelData tree);

    /** See {@code VineData#decode} — blob deserialization through the schema registry. */
    VoxelData decode(byte[] blob);

    /** See {@code VineData#registerNativeField}. */
    void registerNativeField(VineId schemaId, String path, String nativeComponentId);
}
