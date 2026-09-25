package dev.vineengine.vine.brain;

import java.util.Objects;

/**
 * A leaf that does something (sub-08 Stage B): the engine's unit of work, and the
 * only node kind that talks to the world — always through the {@link Primitives} in
 * its context, never through a native call.
 *
 * <p>An action is stateless with respect to the engine: if it needs to remember
 * where it is between ticks it publishes that in the blackboard, so the same
 * instance can serve every actor and a reloaded world resumes identically.
 *
 * @param name the action's name, used in traces and golden files
 * @param step the work, one tick at a time
 */
public record Action(String name, Step step) implements Node {

    /** One tick of an action's work. */
    @FunctionalInterface
    public interface Step {

        /** Runs one tick of this action and reports how far it got. */
        NodeStatus run(BrainContext ctx);
    }

    public Action {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(step, "step");
    }

    /** An action from a lambda. */
    public static Action of(String name, Step step) {
        return new Action(name, step);
    }

    @Override
    public NodeStatus tick(BrainContext ctx) {
        return step.run(ctx);
    }

    @Override
    public String describe() {
        return "Action[" + name + "]";
    }
}
