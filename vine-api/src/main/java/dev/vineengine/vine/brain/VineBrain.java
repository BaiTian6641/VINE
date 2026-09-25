package dev.vineengine.vine.brain;

/**
 * One actor's behaviour runtime (sub-08 Stage B): the engine's own scheduler, ticked
 * by the engine, identical on every cell. It owns a behaviour tree, the actor's
 * memory, and a monotonic tick counter — nothing else. Loading a cell's AI system
 * into it is forbidden (§5.13); the cell only supplies primitives.
 *
 * <p>A brain is created for an actor id and a blackboard tree, and is then ticked
 * once per server tick by whoever owns the actor (the engine's entity runtime from
 * Stage C onward; tests and the TCK tick it directly).
 */
public interface VineBrain {

    /**
     * Installs the tree to run.
     *
     * @throws IllegalStateException if a tree is already installed — replacing a
     *         running brain mid-flight would silently discard actions in progress, so
     *         a consumer that wants a new tree builds a new brain
     */
    void set(Node root);

    /** The installed tree, or {@code null} before {@link #set}. */
    Node root();

    /** The actor's memory. */
    Blackboard blackboard();

    /** Starts building a state machine that lowers to a node (see {@link StateMachineBuilder}). */
    StateMachineBuilder stateMachine();

    /**
     * Advances the brain by one tick and returns the root's status for that tick.
     *
     * @throws IllegalStateException when no tree is installed
     */
    NodeStatus tick(Primitives primitives);

    /** How many ticks this brain has run; also the tick number nodes see. */
    long ticks();
}
