package dev.vineengine.vine.brain;

import java.util.List;

/**
 * Runs its children in order until one does not succeed (sub-08 Stage B): the
 * "and then" of behaviour trees. A {@link NodeStatus#RUNNING} child suspends the
 * sequence — the next tick resumes at that child, not at the first one, which is
 * what makes multi-tick actions compose.
 *
 * @param children the ordered children; at least one
 */
public record Sequence(List<Node> children) implements Node {

    public Sequence {
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("Sequence needs at least one child — an empty sequence has no answer"
                + " to give and would silently succeed");
        }
    }

    /** A sequence of {@code children}. */
    public static Sequence of(Node... children) {
        return new Sequence(List.of(children));
    }

    @Override
    public NodeStatus tick(BrainContext ctx) {
        for (Node child : children) {
            NodeStatus status = child.tick(ctx);
            if (status != NodeStatus.SUCCESS) {
                return status;
            }
        }
        return NodeStatus.SUCCESS;
    }

    @Override
    public String describe() {
        return "Sequence[" + children.stream().map(Node::describe).toList() + "]";
    }
}
