package dev.vineengine.vine.brain;

/**
 * Sugar for the state-machine shape of behaviour (sub-08 Stage B): states with
 * transitions between them, lowering to the same nodes everything else uses. It is
 * sugar, not a second runtime — the built node is a {@link Node} like any other, and
 * the current state lives in the blackboard so a reload resumes where the actor was.
 *
 * <p><b>Reserved key:</b> the machine stores its current state under
 * {@code brain.fsm.state}, declared here so a consumer never guesses and never
 * collides.
 */
public interface StateMachineBuilder {

    /** The state the machine starts in when the blackboard has none yet. */
    StateMachineBuilder initial(String state);

    /** Declares a state's behaviour. A state with no node succeeds immediately. */
    StateMachineBuilder state(String name, Node node);

    /**
     * Declares a transition: while the machine is in {@code from}, the first
     * transition whose condition holds moves it to {@code to}, and the same tick then
     * runs {@code to}'s node — the ordering is declaration order, so the choice is
     * deterministic.
     */
    StateMachineBuilder transition(String from, String to, Condition when);

    /**
     * Builds the node.
     *
     * @throws IllegalStateException when no initial state was declared, when a
     *         transition names an undeclared state, or when a state was declared twice
     */
    Node build();
}
