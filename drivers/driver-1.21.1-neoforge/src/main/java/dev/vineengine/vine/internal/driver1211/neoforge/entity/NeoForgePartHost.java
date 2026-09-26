package dev.vineengine.vine.internal.driver1211.neoforge.entity;

import dev.vineengine.vine.data.EntityTarget;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.internal.entity.PartRuntime;
import dev.vineengine.vine.internal.spi.PartHost;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 NeoForge cell's {@link PartHost} for one live actor (sub-08 Stage D):
 * everything the engine's part runtime needs from this body, and nothing about what the
 * body does with the parts it computes. One instance per {@link VineEntity}, built once
 * by the entity's part path and handed to the engine on every server tick.
 *
 * <p><b>Why the tree is opened here.</b> Part state (wound, broken, flinch stamp) is
 * engine data that has to survive a reload, so it lives under {@code parts.<name>.*} in
 * the actor's own stored tree — and only a cell can open that tree, because the storage
 * attach point is the native entity. It is opened once and cached for the actor's whole
 * life: the engine writes into the instance it is handed, so a fresh tree per tick would
 * both lose those writes and leave the save path persisting a tree nobody wrote to.
 *
 * <p><b>Transform.</b> {@link #position()} and {@link #yawDegrees()} are read live from
 * the body rather than remembered, so the engine's boxes are placed on the actor as it
 * stands this tick — the same values {@link NeoForgeEntityPrimitives} answers a behaviour
 * with, which is what keeps a hitbox where the fight is.
 *
 * <p><b>Invariants:</b> engine vectors and engine data only — no native type crosses the
 * SPI; the actor is the entity this host was built for and never changes.
 */
final class NeoForgePartHost implements PartHost {

    private final VineEntity actor;

    /** The actor's stored tree, opened on first use and handed back unchanged afterwards. */
    private VoxelData state;

    /** Builds the host of {@code actor}; called once per entity by {@link VineEntity}. */
    NeoForgePartHost(VineEntity actor) {
        this.actor = actor;
    }

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
        // Passed through, never re-derived: Minecraft's own yaw is already the evaluator's
        // convention (0 has model +Z facing world +Z, increasing clockwise from above).
        return this.actor.getYRot();
    }
}
