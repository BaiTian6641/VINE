package dev.vineengine.vine.brain;

import java.util.Objects;

/**
 * Wraps a {@link Condition} as a node (sub-08 Stage B): {@link NodeStatus#SUCCESS}
 * when the condition holds, {@link NodeStatus#FAILURE} when it does not. It never
 * reports {@code RUNNING} — a condition that needs time is an action, not a
 * condition, and pretending otherwise would hide a design mistake inside the tree.
 *
 * @param name the condition's name, used in traces and golden files
 * @param condition the predicate
 */
public record ConditionLeaf(String name, Condition condition) implements Node {

    public ConditionLeaf {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(condition, "condition");
    }

    @Override
    public NodeStatus tick(BrainContext ctx) {
        return condition.test(ctx) ? NodeStatus.SUCCESS : NodeStatus.FAILURE;
    }

    @Override
    public String describe() {
        return "Condition[" + name + "]";
    }
}
