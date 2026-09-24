package dev.vineengine.vine.testmod.content;

/**
 * The enum axis of the Stage B state exemplar block: three constants, declared
 * order significant — the engine's flattened state model takes the declaration
 * order as the property's value order, and the first value is the default a cell
 * materializes.
 */
public enum TestStateMode {

    /** The default mode: no work in flight. */
    IDLE,

    /** A middle constant, so the exemplar exercises a non-terminal subset. */
    WAITING,

    /** The last constant, used by the round-trip scenario's mutation step. */
    CHARGED
}
