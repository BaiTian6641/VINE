package dev.vineengine.vine.content;

/**
 * Why a {@link TickBehavior} is running (sub-07 Stage C): the engine normalizes the
 * three signals a cell can carry, so one behavior implementation covers the worlds
 * of both loaders without branching on which one it is on.
 */
public enum TickKind {

    /** A vanilla random tick landed on the block. */
    RANDOM,

    /** A scheduled tick the block (or something else) requested. */
    SCHEDULED,

    /** The holder's own block-entity ticker, its interval already applied. */
    BLOCK_ENTITY
}
