package dev.vineengine.vine.brain;

/**
 * The three outcomes a behaviour node can report (sub-08 Stage B). Three, not two,
 * because a behaviour tree that cannot say "not finished yet" has to fake
 * instants: {@link #RUNNING} is what makes a long action (walking somewhere,
 * winding up an attack) expressible without blocking the tick.
 */
public enum NodeStatus {

    /** The node finished what it started, successfully. */
    SUCCESS,

    /** The node cannot do its job — its parent may try something else. */
    FAILURE,

    /** The node is mid-action and must be ticked again before it can answer. */
    RUNNING
}
