package dev.vineengine.vine.internal.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.registry.VineId;

/**
 * Engine-side schema registry (sub-03 §2 internals): {@code VineId →
 * (current version, fixer chain)}. Registration is a boot-time consumer act;
 * loading routes through the chain — older blobs fix up lazily, newer blobs
 * open read-only.
 *
 * <p>Follows the {@code DescriptorStore} engine conventions: duplicate ids
 * throw, fixer chains must be complete, writes refuse after {@link #freeze()}.
 * The freeze hook is wired into {@code REGISTRIES_FROZEN} together with the
 * Stage-C {@code VineData} facade; until then a standalone instance is the
 * harness/runtime entry point.
 */
public final class SchemaRegistry {

    /** One registered schema plus its complete v1→current fixer chain. */
    public record SchemaEntry(VoxelSchema schema, List<VoxelDataFixer> fixers) {
    }

    private final Map<VineId, SchemaEntry> entries = new HashMap<>();
    private boolean frozen;

    /**
     * Registers {@code schema} with its fixer chain.
     *
     * @throws IllegalArgumentException if the fixer chain is not exactly
     *         {@code schema.version() - 1} steps long (the chain must carry
     *         any v1 blob to current — gaps would strand old saves)
     * @throws IllegalStateException if the id is already registered or the
     *         registry is frozen
     */
    public synchronized void registerSchema(VoxelSchema schema, List<VoxelDataFixer> fixers) {
        checkWritable("registerSchema");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(fixers, "fixers");
        if (entries.containsKey(schema.id())) {
            throw new IllegalStateException("schema already registered: " + schema.id());
        }
        int expected = schema.version() - 1;
        if (fixers.size() != expected) {
            throw new IllegalArgumentException(
                "schema " + schema.id() + " v" + schema.version() + " needs exactly " + expected
                    + " fixers (complete chain v1→v" + schema.version() + "), got " + fixers.size());
        }
        for (VoxelDataFixer fixer : fixers) {
            Objects.requireNonNull(fixer, "fixer");
        }
        entries.put(schema.id(), new SchemaEntry(schema, List.copyOf(fixers)));
    }

    /** Registered entry for {@code id}, if any. */
    public synchronized Optional<SchemaEntry> get(VineId id) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(id, "id")));
    }

    /**
     * Creates an empty writable tree at the schema's current version.
     *
     * @throws IllegalArgumentException if the schema is not registered — the
     *         engine never guesses a version for unknown content
     */
    public synchronized VoxelData create(VineId schemaId) {
        SchemaEntry entry = entries.get(Objects.requireNonNull(schemaId, "schemaId"));
        if (entry == null) {
            throw new IllegalArgumentException(
                "unknown schema: " + schemaId + " — register it before creating trees");
        }
        return new VoxelDataImpl(new TreeState(schemaId, entry.schema().version()));
    }

    /** Freezes registration; idempotent, reads stay open. */
    public synchronized void freeze() {
        frozen = true;
    }

    public synchronized boolean frozen() {
        return frozen;
    }

    private void checkWritable(String action) {
        if (frozen) {
            throw new IllegalStateException("schema registry is frozen — " + action + " refused");
        }
    }
}
