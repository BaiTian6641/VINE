package dev.vineengine.vine.brain;

import java.util.Objects;

import dev.vineengine.vine.registry.VineId;

/**
 * Everything a node may consult for one tick (sub-08 Stage B): the actor it is
 * running for, the tick number, its memory and the world primitives. Nodes receive
 * this as their only input, which is what makes a brain re-tickable and traceable —
 * a node that reaches for anything else breaks the determinism contract.
 *
 * @param actorId    the entity this brain belongs to
 * @param tick       the server tick this tick represents (monotonic, from the caller)
 * @param blackboard the actor's memory
 * @param primitives the world operations available this tick
 */
public record BrainContext(VineId actorId, long tick, Blackboard blackboard, Primitives primitives) {

    public BrainContext {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(blackboard, "blackboard");
        Objects.requireNonNull(primitives, "primitives");
    }

    /** The same context one tick later — what the runtime hands each node by default. */
    public BrainContext nextTick() {
        return new BrainContext(actorId, tick + 1, blackboard, primitives);
    }
}
