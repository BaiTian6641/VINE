package dev.vineengine.vine.brain;

import java.util.List;

/**
 * Tries its children in order until one does not fail (sub-08 Stage B): the "or
 * else" of behaviour trees, and the usual way to write "if X, else if Y, else Z". A
 * {@link NodeStatus#RUNNING} child suspends the selector.
 *
 * @param children the ordered children; at least one
 */
public record Selector(List<Node> children) implements Node {

    public Selector {
        children = List.copyOf(children);
        if (children.isEmpty()) {
            throw new IllegalArgumentException("Selector needs at least one child — an empty selector can only fail"
                + " and hides the author's intent");
        }
    }

    /** A selector of {@code children}. */
    public static Selector of(Node... children) {
        return new Selector(List.of(children));
    }

    @Override
    public NodeStatus tick(BrainContext ctx) {
        for (Node child : children) {
            NodeStatus status = child.tick(ctx);
            if (status != NodeStatus.FAILURE) {
                return status;
            }
        }
        return NodeStatus.FAILURE;
    }

    @Override
    public String describe() {
        return "Selector[" + children.stream().map(Node::describe).toList() + "]";
    }
}
