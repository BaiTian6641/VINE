package dev.vineengine.vine.content;

/**
 * A behavior that answers a block interaction (sub-07 Stage C): the engine's
 * normalized right-click-a-block hook. Cells that observe the native interaction
 * post it into the engine's dispatch; the behaviors run in the descriptor's
 * declared order and the first one that does not {@linkplain UseResult#PASS pass}
 * decides the outcome.
 *
 * <p>The context is an engine view: it carries the engine world handle, the
 * position, the block's engine state, the interacting {@code VinePlayer}, the hand,
 * the clicked face and the hit point — no loader or game type crosses it, so the
 * same behavior implementation runs unchanged on every cell.
 */
public non-sealed interface UseBehavior extends BlockBehavior {

    /**
     * Handles one interaction at {@code ctx}'s position.
     *
     * @return {@link UseResult#PASS} to let the next behavior (or the cell's own
     *         default handling) continue, {@link UseResult#HANDLED} to consume the
     *         interaction, or {@link UseResult#DENIED} to refuse it outright; never
     *         {@code null}
     */
    UseResult onUse(BlockUseContext ctx);
}
