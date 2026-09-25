package dev.vineengine.vine.content;

import dev.vineengine.vine.registry.VineId;

/**
 * Where a {@link LootBehavior} writes (sub-07 Stage C): an engine-owned sink of
 * item stacks, so a behavior states what should drop without knowing whether its
 * cell expresses drops as a loot table, a loot modifier or a direct drop call.
 *
 * <p><b>Invariants:</b> additions accumulate in call order and are applied by the
 * cell after every behavior has run; the sink is only valid for the duration of the
 * {@link LootBehavior#modifyLoot} call it was handed to.
 */
public interface LootSink {

    /**
     * Adds a drop of {@code count} many {@code itemId}.
     *
     * @throws IllegalArgumentException if {@code count} is not positive or the id is
     *         not a registered engine item
     */
    void add(VineId itemId, int count);
}
