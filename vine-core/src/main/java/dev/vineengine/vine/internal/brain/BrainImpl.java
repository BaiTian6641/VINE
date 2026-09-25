package dev.vineengine.vine.internal.brain;

import java.util.Objects;

import dev.vineengine.vine.brain.Blackboard;
import dev.vineengine.vine.brain.BrainContext;
import dev.vineengine.vine.brain.Node;
import dev.vineengine.vine.brain.NodeStatus;
import dev.vineengine.vine.brain.Primitives;
import dev.vineengine.vine.brain.StateMachineBuilder;
import dev.vineengine.vine.brain.VineBrain;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

/**
 * The engine's brain runtime (sub-08 Stage B). One instance per actor; it holds the
 * tree, the actor's memory (the caller's {@code VoxelData} tree, not a copy), and a
 * monotonic tick counter. Ticking is pure with respect to the engine: the only
 * outside world it can reach is the {@link Primitives} it is handed, which is what
 * makes a golden trace meaningful.
 */
public final class BrainImpl implements VineBrain {

    private final VineId actorId;
    private final Blackboard blackboard;
    private Node root;
    private long ticks;

    public BrainImpl(VineId actorId, VoxelData memory) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.blackboard = new BlackboardImpl(Objects.requireNonNull(memory, "memory"));
    }

    @Override
    public void set(Node root) {
        Objects.requireNonNull(root, "root");
        if (this.root != null) {
            throw new IllegalStateException("brain for " + actorId + " already has a tree installed — replacing a"
                + " running brain would discard actions in progress; build a new brain instead");
        }
        this.root = root;
    }

    @Override
    public Node root() {
        return root;
    }

    @Override
    public Blackboard blackboard() {
        return blackboard;
    }

    @Override
    public StateMachineBuilder stateMachine() {
        return new StateMachineBuilderImpl("actor");
    }

    @Override
    public NodeStatus tick(Primitives primitives) {
        Objects.requireNonNull(primitives, "primitives");
        if (root == null) {
            throw new IllegalStateException("brain for " + actorId + " has no tree installed — set one before ticking");
        }
        BrainContext ctx = new BrainContext(actorId, ticks, blackboard, primitives);
        NodeStatus status = root.tick(ctx);
        ticks++;
        return status;
    }

    @Override
    public long ticks() {
        return ticks;
    }

    @Override
    public String toString() {
        return "VineBrain[" + actorId + " ticks=" + ticks + " root=" + (root == null ? "none" : root.describe()) + "]";
    }
}
