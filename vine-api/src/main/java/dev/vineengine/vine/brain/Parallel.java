package dev.vineengine.vine.brain;

import java.util.List;

/**
 * Ticks every child each tick and decides from their statuses under a declared
 * policy (sub-08 Stage B). The policy is explicit because "parallel" without one is
 * ambiguous: a policy that needs every child to succeed cannot be satisfied once
 * one fails, and a policy that needs one cannot be satisfied once all have failed —
 * the node answers as soon as the outcome is decided, and reports
 * {@link NodeStatus#RUNNING} while it is still undecided.
 *
 * @param policy   what counts as success
 * @param children the children, ticked in declaration order every tick; at least one
 */
public record Parallel(Policy policy, List<Node> children) implements Node {

    /** What a parallel node considers success. */
    public enum Policy {

        /** Succeeds only when every child has succeeded; fails as soon as one fails. */
        ALL_SUCCEED,

        /** Succeeds as soon as one child succeeds; fails only when every child has failed. */
        ANY_SUCCEED
    }

    public Parallel {
        policy = java.util.Objects.requireNonNull(policy, "policy");
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("Parallel needs at least one child — an empty parallel has no policy to"
                + " apply");
        }
    }

    @Override
    public NodeStatus tick(BrainContext ctx) {
        int succeeded = 0;
        int failed = 0;
        for (Node child : children) {
            switch (child.tick(ctx)) {
                case SUCCESS -> succeeded++;
                case FAILURE -> failed++;
                case RUNNING -> {
                    // still undecided for this child
                }
            }
        }
        return switch (policy) {
            case ALL_SUCCEED -> failed > 0 ? NodeStatus.FAILURE
                : succeeded == children.size() ? NodeStatus.SUCCESS : NodeStatus.RUNNING;
            case ANY_SUCCEED -> succeeded > 0 ? NodeStatus.SUCCESS
                : failed == children.size() ? NodeStatus.FAILURE : NodeStatus.RUNNING;
        };
    }

    @Override
    public String describe() {
        return "Parallel[" + policy + ";" + children.stream().map(Node::describe).toList() + "]";
    }
}
