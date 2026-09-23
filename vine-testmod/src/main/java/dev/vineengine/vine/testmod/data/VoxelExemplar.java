package dev.vineengine.vine.testmod.data;

import java.util.List;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.FieldStrategy;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.registry.VineId;

/**
 * VoxelData exemplar (sub-03 Stage C, sub-22 content contract: one canonical
 * exemplar per surface): one schema carrying <b>one portable field and one
 * native field</b> — the two persistence strategies of sub-03 §2 in one tree.
 *
 * <p>{@code stats.mana} is portable (default): it rides the engine-owned
 * {@code vine:voxel_data} component with identical semantics on every cell.
 * {@code stats.damage} is native (opt-in): it maps to {@code
 * minecraft:damage} for vanilla anvil/grindstone interop — the per-cell
 * mapping activates with the sub-03 Stage D drivers, which is also when the
 * {@code voxeldata.native_strategy} TCK scenario exercises it; until then the
 * strategy constants document the field's declared home.
 */
public final class VoxelExemplar {

    /** Schema identity: {@code vine_test:voxel}, version 1, no fixers. */
    public static final VineId SCHEMA_ID = VineId.of("vine_test", "voxel");

    /** Portable field (default strategy): engine-owned carrier on every cell. */
    public static final String MANA_PATH = "stats.mana";

    public static final FieldStrategy MANA_STRATEGY = new FieldStrategy.Portable();

    /** Native field: mirrored to vanilla damage; re-read on access, never cached. */
    public static final String DAMAGE_PATH = "stats.damage";

    public static final FieldStrategy DAMAGE_STRATEGY = new FieldStrategy.Native("minecraft:damage");

    /** Placeholder payload codec — the engine never invokes it for versioning. */
    private static final Codec<VoxelData> CODEC = Codec.unit(null);

    private VoxelExemplar() {
    }

    /**
     * Registers the schema. Called from the testmod initializer at
     * {@code REGISTRIES_OPEN} (the registration contract) — after
     * {@code REGISTRIES_FROZEN} this would throw, by design.
     */
    public static void register() {
        VineData.registerSchema(new VoxelSchema(SCHEMA_ID, 1, CODEC), List.of());
    }

    /**
     * Creates an in-memory tree seeded with both fields — the sub-22 sub-03
     * acceptance shape: register a schema, create a tree in memory.
     */
    public static VoxelData createTree() {
        VoxelData tree = VineData.create(SCHEMA_ID);
        tree.put(MANA_PATH, 100);
        tree.put(DAMAGE_PATH, 3);
        return tree;
    }
}
