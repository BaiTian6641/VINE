package dev.vineengine.vine.world;

/**
 * Which hand performed an interaction (sub-07 Stage C). Exactly the two a cell
 * exposes; a behavior that must not double-handle checks it explicitly, because the
 * engine reports each hand's interaction separately.
 */
public enum Hand {

    /** The player's main hand. */
    MAIN,

    /** The player's off hand. */
    OFF
}
