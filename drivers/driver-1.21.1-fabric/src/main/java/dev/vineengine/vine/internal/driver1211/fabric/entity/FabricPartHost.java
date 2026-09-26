package dev.vineengine.vine.internal.driver1211.fabric.entity;

import dev.vineengine.vine.data.EntityTarget;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.internal.entity.PartRuntime;
import dev.vineengine.vine.internal.spi.PartHost;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 Fabric cell's {@link PartHost} for one actor (sub-08 Stage D): everything
 * the engine's part runtime needs to know about this cell's body — where it stands,
 * which way it faces, and the persistent tree its part state lives in — and nothing
 * about what this cell does with the parts the engine computes.
 *
 * <p><b>Built once per actor and reused.</b> It holds no per-tick state: the position
 * and yaw are read live from the entity, so the engine's geometry follows this body
 * instead of a value the host remembered. {@link VineEntity} builds exactly one and
 * hands the same instance to {@link PartRuntime#tick} every server tick.
 *
 * <p><b>The tree is opened once, deliberately.</b> This cell's store answers
 * {@code VineData.of} by loading the actor's stored payload (or creating a fresh tree
 * for an actor that has none) — a <em>new</em> instance every call, not a cached one.
 * Handing the engine a fresh copy each tick would discard every wound written since
 * the previous tick, so the first {@link #state()} call is the one that decides the
 * tree for the actor's whole life, and the engine keeps writing into that same tree,
 * which is the one this cell's save path already flushes with the actor.
 *
 * <p><b>Invariants:</b> the actor is the entity this host was built for and never
 * changes; no native type crosses this class — the tree, a position and a yaw, exactly
 * the interface's shape. {@code PartHost} is deliberately not exposed outside the
 * driver jar, so the engine cannot reach the native body through it.
 */
public final class FabricPartHost implements PartHost {

    private final VineEntity actor;

    /** The actor's part-state tree, opened on the first ask and kept for its whole life. */
    private VoxelData state;

    /** Builds the host for {@code actor}; called once per actor by {@link VineEntity}. */
    FabricPartHost(VineEntity actor) {
        this.actor = actor;
    }

    /**
     * The actor's persistent engine tree under the part schema — the same tree the
     * save path writes, opened exactly once (see the class javadoc: the store returns
     * a fresh tree per call, so the first instance is the only one that can hold a
     * wound across ticks).
     */
    @Override
    public VoxelData state() {
        VoxelData tree = this.state;
        if (tree == null) {
            this.state = tree = VineData.of(new EntityTarget(this.actor), PartRuntime.SCHEMA);
        }
        return tree;
    }

    @Override
    public Vec3 position() {
        return Vec3.of(this.actor.getX(), this.actor.getY(), this.actor.getZ());
    }

    @Override
    public float yawDegrees() {
        // Minecraft's own yaw convention is the evaluator's (see
        // PoseEvaluator#partBox), so this is the descriptor's angle, unadjusted:
        // "fixing" it here would rotate every part on this cell alone.
        return this.actor.getYaw();
    }
}
