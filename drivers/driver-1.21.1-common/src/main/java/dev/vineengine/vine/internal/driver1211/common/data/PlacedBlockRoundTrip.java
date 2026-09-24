package dev.vineengine.vine.internal.driver1211.common.data;

import dev.vineengine.vine.data.BlockEntityTarget;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

/**
 * The placed-block acceptance leg (sub-07 Stage C / sub-22 Stage B): a real
 * block entity in a real world, written through the engine API the first time the
 * world is loaded and verified on the next load — place, write, save, reload,
 * assert, with the world's own persistence doing the carrying.
 *
 * <p>Kept deliberately tiny: the cell resolves the block entity (it owns world
 * access) and this leg owns the engine-side write/read and its verdict, so both
 * cells print the same line and the TCK asserts one shape.
 */
public final class PlacedBlockRoundTrip {

    private static final VineId SCHEMA = VineId.of("vine", "probe_placed_block");
    private static final String PATH = "payload.marks";

    private PlacedBlockRoundTrip() {
    }

    /** Registers the probe's schema (boot-time, before the registry freeze). */
    public static void registerSchema() {
        VineData.registerSchema(new dev.vineengine.vine.data.VoxelSchema(SCHEMA, 1,
            com.mojang.serialization.Codec.unit(null)), java.util.List.of());
    }

    /** Writes on the first boot, verifies on any later one. */
    public static String run(VoxelProbe.AttachmentHolders holders) {
        if (!(holders instanceof VoxelProbe.PlacedBlockHolders placed)) {
            return "skipped (cell does not place blocks)";
        }
        Object blockEntity = placed.placedBlockEntity(VineId.of("vine_test", "testblock"));
        if (blockEntity == null) {
            return "skipped (no block entity at the probe position)";
        }
        var target = new BlockEntityTarget(blockEntity);
        VoxelData tree = VineData.of(target, SCHEMA);
        int stored = tree.getInt(PATH);
        if (stored == 0) {
            tree.put(PATH, 417);
            var driver = dev.vineengine.vine.internal.data.VoxelStorageBinding.bound();
            var engine = dev.vineengine.vine.internal.data.VoxelStorageBinding.engine();
            if (driver == null || engine == null) {
                return "skipped (no storage driver bound)";
            }
            driver.flushDirty(target, SCHEMA, engine.drainDirty(tree));
            return "written=" + tree.getInt(PATH) + " (verdict after reload)";
        }
        return "survived=" + stored + " (verdict after reload)";
    }
}
