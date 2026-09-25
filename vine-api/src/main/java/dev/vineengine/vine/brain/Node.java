package dev.vineengine.vine.brain;

/**
 * One node of a behaviour tree (sub-08 Stage B): the engine's own runtime, never a
 * wrapper over a loader's AI system (§5.13). A node is pure with respect to the
 * engine — everything it remembers between ticks lives in the blackboard or in the
 * primitives' own state, so a tree can be re-ticked after a reload and behave the
 * same way.
 *
 * <p><b>Determinism contract:</b> a node may only act through its
 * {@link BrainContext} (blackboard + primitives), must not read wall-clock time or
 * unseeded randomness, and must answer the same status for the same context and the
 * same primitive results. The golden tick-trace TCK is the tripwire: it replays a
 * recorded primitive-result tape and compares the whole trace, so a node that
 * sneaks in a second source of nondeterminism fails on both cells at once.
 *
 * <p>The family is sealed: this interface plus {@link Sequence}, {@link Selector},
 * {@link Parallel}, {@link Condition} and {@link Action} are the engine's whole
 * vocabulary, so a cell can never smuggle loader-specific behaviour in through a
 * node. Consumers compose; they do not subclass.
 */
public sealed interface Node permits Sequence, Selector, Parallel, ConditionLeaf, Action {

    /** Runs (or resumes) this node for one tick. */
    NodeStatus tick(BrainContext ctx);

    /** A short, stable description for traces and golden files (never a native class name). */
    default String describe() {
        return getClass().getSimpleName();
    }
}
