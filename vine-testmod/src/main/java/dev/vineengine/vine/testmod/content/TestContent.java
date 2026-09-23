package dev.vineengine.vine.testmod.content;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.BlockTuning;
import dev.vineengine.vine.content.ItemDescriptor;
import dev.vineengine.vine.content.ItemTuning;
import dev.vineengine.vine.content.ModelHint;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

/**
 * Block/item registration for the sub-07 Stage A exemplars: exactly one block
 * and one item, authored as engine descriptors and materialized by whichever
 * cell is running — the same sources on every driver (Prime Invariant).
 *
 * <p>Ids are pinned by the TCK scenarios {@code vine_test:block_place_break}
 * and {@code vine_test:save_reload} ({@code PlaceBlock vine_test:testblock});
 * the item id follows the same convention. Both land in the engine store here
 * (Java path) and in the vanilla BLOCK/ITEM registries through the driver's
 * materializer — the testmod never touches a loader or game class.
 *
 * <p>Stage A shape: id + minimal tuning + placeholder model hint only; the
 * descriptor types are engine-owned (defined by vine-core at boot), so this
 * code only registers — never defines.
 */
public final class TestContent {

    /** The single block exemplar; pinned by the TCK block scenarios. */
    public static final VineId TESTBLOCK_ID = VineId.of("vine_test", "testblock");

    /** The single item exemplar. */
    public static final VineId TESTITEM_ID = VineId.of("vine_test", "testitem");

    private TestContent() {
    }

    /**
     * Registers both exemplars and arms the post-freeze probe: after
     * REGISTRIES_FROZEN both holders must resolve through the engine tokens,
     * proving the structural entries survived the freeze. The probe prints to
     * stdout so headless boot logs carry it regardless of the cell's logging
     * setup (same contract as the marker exemplar).
     */
    public static void register(VineEngine engine) {
        VineRegistries.register(VineContent.BLOCK_TYPE, TESTBLOCK_ID,
            new BlockDescriptor(TESTBLOCK_ID, BlockTuning.STONE_LIKE, ModelHint.cubeAll()));
        VineRegistries.register(VineContent.ITEM_TYPE, TESTITEM_ID,
            new ItemDescriptor(TESTITEM_ID, new ItemTuning(64), ModelHint.generated()));
        engine.onPhase(EnginePhase.REGISTRIES_FROZEN, change -> {
            var block = VineRegistries.get(VineContent.BLOCK_TYPE, TESTBLOCK_ID)
                .orElseThrow(() -> new IllegalStateException(
                    "testmod block vanished at freeze — structural registration broken"));
            var item = VineRegistries.get(VineContent.ITEM_TYPE, TESTITEM_ID)
                .orElseThrow(() -> new IllegalStateException(
                    "testmod item vanished at freeze — structural registration broken"));
            System.out.println("vine-testmod: content holders resolved post-freeze block="
                + block.id() + " item=" + item.id());
        });
    }
}
