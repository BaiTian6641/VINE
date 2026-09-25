package dev.vineengine.vine.world;

/**
 * An engine block face (sub-07 Stage C): the six axis directions a cell's own
 * direction enum always has, named engine-side so a behavior never names a game
 * class. Ordering is the vanilla one (down, up, north, south, west, east), which is
 * also the order a cell's enum uses — a driver maps by ordinal-free lookup, never by
 * assuming the ordinal, but the shared order keeps traces readable.
 */
public enum Direction {

    /** Negative Y. */
    DOWN,

    /** Positive Y. */
    UP,

    /** Negative Z. */
    NORTH,

    /** Positive Z. */
    SOUTH,

    /** Negative X. */
    WEST,

    /** Positive X. */
    EAST
}
