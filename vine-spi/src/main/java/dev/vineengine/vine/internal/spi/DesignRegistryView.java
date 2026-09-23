package dev.vineengine.vine.internal.spi;

import java.util.List;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.registry.VineId;

/**
 * Driver-facing view of the DESIGN descriptor types (sub-02 §2): each type
 * rides a vanilla dynamic datapack registry, so its entries live in datapacks
 * at {@code data/<entry-ns>/<registry-ns>/<registry-path>/<entry>.json} and
 * load per world. The driver registers each type at its loader's dynamic
 * registry moment and reports whatever the datapack loader produced back to the
 * engine — the engine never parses JSON itself.
 *
 * <p>Snapshot per call; valid from {@code REGISTRIES_OPEN} on.
 */
public interface DesignRegistryView {

    /** Every defined DESIGN type, sorted by registry id for deterministic iteration. */
    List<DesignType> types();

    /** One design type as the driver needs it. */
    interface DesignType {

        /** The type's registry id ({@code mymod:ability}); the dynamic registry key. */
        VineId registryId();

        /** The descriptor codec: dynamic-registry JSON decodes through it. */
        Codec<?> codec();

        /** Whether the engine declares this registry synced to clients (§5.2). */
        boolean syncToClient();

        /** Whether vanilla's SKIP_WHEN_EMPTY applies on the wire. */
        boolean skipWhenEmpty();
    }
}
