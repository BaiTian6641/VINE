package dev.vineengine.vine.content;

/**
 * A behavior that modifies what a block drops (sub-07 Stage C): the engine's
 * normalized loot hook, declared as a modifier rather than as a replacement table,
 * so a consumer's behavior composes with the block's own drops instead of
 * redefining them.
 *
 * <p>Cells that own a loot-table mechanism post their block's resolved drops into
 * the engine's dispatch and then add whatever the behaviors contributed; a block
 * with no {@code LootBehavior} installs no modifier at all.
 */
public non-sealed interface LootBehavior extends BlockBehavior {

    /**
     * Adds to or filters the drops for {@code ctx}'s position by writing into
     * {@code out}.
     */
    void modifyLoot(LootContext ctx, LootSink out);
}
