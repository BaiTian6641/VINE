package dev.vineengine.vine.internal.data;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.vineengine.vine.registry.VineId;

/**
 * Per-field native interop mapping (sub-03 §2, {@code FieldStrategy.Native}):
 * a schema's path may mirror a vanilla data component instead of the portable
 * {@code vine:voxel_data} payload, so game systems that already touch that
 * component see the same value the engine sees.
 *
 * <p>M1 scope: the mapping is declared before the schema store freezes and read
 * mechanically by the drivers — a driver resolves the component id
 * ({@code "minecraft:damage"}) to its own native component and copies the value
 * in both directions.
 */
public final class NativeFields {

    private static final Map<VineId, Map<String, String>> BY_SCHEMA = new LinkedHashMap<>();

    private NativeFields() {
    }

    /** Declares that {@code path} of {@code schemaId} mirrors {@code nativeComponentId}. */
    public static synchronized void register(VineId schemaId, String path, String nativeComponentId) {
        BY_SCHEMA.computeIfAbsent(schemaId, id -> new LinkedHashMap<>()).put(path, nativeComponentId);
    }

    /** The schema's path → native component id mapping (empty when none). */
    public static synchronized Map<String, String> fieldsFor(VineId schemaId) {
        Map<String, String> fields = BY_SCHEMA.get(schemaId);
        return fields == null ? Map.of() : Map.copyOf(fields);
    }
}
