package dev.vineengine.vine.brain;

import java.util.List;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The world operations a brain may use (sub-08 Stage B): the *only* way a behaviour
 * reaches past its own memory. On a live cell these are driver primitives (Stage C);
 * in a headless run they are a recorded tape, and because both implement this one
 * interface, the same behaviour tree produces the same trace in both — which is what
 * the golden tick-trace checks.
 *
 * <p><b>Primitives only, never a scheduler:</b> nothing here decides what an actor
 * does next. Each call either answers a question or advances motion the caller asked
 * for; a cell never runs a goal selector behind the engine's back (§5.13).
 *
 * <p><b>Invariants:</b> ids and engine vectors only — no native type crosses this
 * interface; an operation that cannot be satisfied reports the failure value defined
 * here rather than throwing for an ordinary world condition.
 */
public interface Primitives {

    /**
     * Where the actor is right now. A behaviour needs its own position to reason about
     * distance and arrival, and taking it from the world each tick keeps the answer
     * truthfully live rather than a value the behaviour remembers and drifts with.
     */
    Vec3 position();

    /**
     * Requests a path to {@code target} and reports this tick's outcome. A cell may
     * answer {@link PathOutcome#PENDING} while it computes asynchronously; the caller
     * is expected to ask again next tick.
     */
    PathOutcome requestPath(Vec3 target);

    /** Advances one step along the most recent successful path. */
    StepOutcome stepPath();

    /** Whether {@code target} is currently visible from the actor's eyes. */
    boolean hasLineOfSight(VineId target);

    /** The actors of any kind within {@code radius} blocks, in a deterministic order. */
    List<VineId> queryTargets(double radius);

    /** Turns the actor's head/body toward {@code target} (a no-op when it is gone). */
    void lookAt(VineId target);

    /**
     * Takes the pending flinch, if a part of this actor crossed its flinch threshold
     * since the last call (sub-08 Stage D). This is how a hit *interrupts* a behaviour:
     * a node checks it, sees that the beast was just staggered, and yields to the
     * stagger action instead of finishing its swing. Consuming rather than reading, so
     * one flinch interrupts exactly once even when several nodes check in a tick.
     */
    boolean consumeFlinch();

    /** What a path request produced. */
    enum PathOutcome {

        /** A path exists; {@link #stepPath()} may now advance along it. */
        FOUND,

        /** No path exists to that target — the caller should pick another goal. */
        UNREACHABLE,

        /** Still being computed; ask again next tick. */
        PENDING
    }

    /** What one step along the current path produced. */
    enum StepOutcome {

        /** Moved closer; more steps remain. */
        ADVANCED,

        /** Reached the end of the path. */
        ARRIVED,

        /** Could not move (blocked, no path, or the actor cannot move right now). */
        BLOCKED
    }
}
