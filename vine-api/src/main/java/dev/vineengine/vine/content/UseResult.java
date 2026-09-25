package dev.vineengine.vine.content;

/**
 * The outcome of one {@link UseBehavior} (sub-07 Stage C). Ordered by strength, so
 * dispatch can stop at the first behavior that is not {@link #PASS}: a cell maps
 * these onto its own interaction result without the engine having to know what that
 * result type is.
 *
 * <p>{@link #HANDLED} and {@link #DENIED} both stop the dispatch chain, and the
 * difference is what the cell tells the game: handled means the interaction was
 * consumed (the item is not used, the block did its thing), denied means it is
 * refused outright (the cell refuses the interaction as a whole).
 */
public enum UseResult {

    /** Not this behavior's interaction: continue the chain, then the cell's default. */
    PASS,

    /** Consumed here: stop the chain and let the cell report a successful use. */
    HANDLED,

    /** Refused here: stop the chain and let the cell report the interaction as cancelled. */
    DENIED
}
