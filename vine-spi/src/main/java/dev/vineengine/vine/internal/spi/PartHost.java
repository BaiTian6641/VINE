package dev.vineengine.vine.internal.spi;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.world.Vec3;

/**
 * The driver-side half of multipart hosting (sub-08 Stage D): everything the engine's
 * part runtime needs from a cell about one actor, and nothing about what the cell does
 * with it. Implemented by each cell per actor and handed to the engine once per server
 * tick, next to the actor's {@code Primitives}.
 *
 * <p><b>Who owns what.</b> The engine owns part <em>state</em> (wound totals, broken
 * flags, flinch stamps) and all of the geometry maths — a cell must not keep a second
 * copy of either, or two cells would disagree about what a broken tail means. A cell
 * owns the actor's body: where it stands, which way it faces, and what native bodies it
 * spawns to carry the parts the engine computed.
 *
 * <p><b>Why the storage tree is handed over rather than looked up.</b> Part state has to
 * survive a reload, so it lives in the actor's own stored tree — which only a cell can
 * open, because only a cell has the native entity. Passing the tree in keeps the engine
 * free of native types while still making the state persistent; a headless harness
 * passes a detached tree, and the same runtime code runs unchanged.
 *
 * <p><b>Invariants:</b> no native type crosses this interface — engine vectors and
 * engine data only; {@link #state()} returns the same tree for the actor's whole life
 * (the engine caches it per tick, so returning a fresh copy each call would lose writes).
 */
public interface PartHost {

    /**
     * The actor's persistent engine tree — the one the save path already writes. Part
     * state lives under {@code parts.<name>.*} in it, so a wound needs no mechanism of
     * its own.
     */
    VoxelData state();

    /** Where the actor stands right now, in the cell's own world coordinates. */
    Vec3 position();

    /** Which way the actor faces, in the same yaw convention as the pose evaluator. */
    float yawDegrees();
}
