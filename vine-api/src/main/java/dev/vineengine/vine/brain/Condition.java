package dev.vineengine.vine.brain;

/**
 * A predicate over the brain's context (sub-08 Stage B): the leaf of every
 * decision. Java-authored conditions implement this directly; design-authored
 * conditions arrive as data through sub-02's dynamic registries, and both end up
 * as a {@link Node}.
 */
@FunctionalInterface
public interface Condition {

    /** Whether the condition holds for the current tick. */
    boolean test(BrainContext ctx);
}
