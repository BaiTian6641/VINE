package dev.vineengine.vine.internal.spi;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The driver-side entity SPI (sub-08 §2 "Driver contract"): the primitives a cell
 * supplies so the engine's own entity runtime can act inside a live world. One per
 * cell, implemented only by VINE's own driver jars, bound once during driver
 * bootstrap via vine-core's {@code EntityBinding}.
 *
 * <p><b>Primitives only — never a scheduler.</b> A cell answers "spawn one", "where
 * is the ground here", "request a path" and the like; it never decides what an
 * entity <em>does</em>. Mapping {@code VineBrain} onto a loader's goal-selector or
 * {@code Brain} system is forbidden (§5.13): the behaviour scheduler is engine-side
 * so an entity behaves identically on every cell, and the determinism contract
 * (golden tick-traces, sub-08 Stage B) stays checkable.
 *
 * <p><b>Invariants:</b> no native type crosses this interface — ids, engine
 * positions and engine values only; a method that cannot satisfy its request
 * reports {@code false}/empty rather than throwing for an ordinary world condition
 * (an unloaded dimension, a position no engine block occupies). Stage A ships
 * {@link #spawn}; the remaining primitives (pathing, look, line of sight, target
 * queries, rider input) arrive with Stage C and are added to this interface, never
 * as a second parallel seam.
 */
public interface EntityDriver {

    /**
     * Spawns one {@code entityId} at {@code position} in {@code dimensionId}.
     *
     * @return {@code true} when a fresh entity of that descriptor exists at that
     *         position afterwards; {@code false} when the cell refused (dimension
     *         not loaded, position outside the world, descriptor not materialized)
     */
    boolean spawn(VineId entityId, VineId dimensionId, Vec3 position);
}
