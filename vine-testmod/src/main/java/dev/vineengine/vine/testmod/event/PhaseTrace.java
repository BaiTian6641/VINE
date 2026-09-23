package dev.vineengine.vine.testmod.event;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.VineEngine;

/**
 * Canonical engine-event exemplar (sub-01, M0): one subscriber over the
 * engine's phase-change events.
 *
 * <p>Why this shape: phases are replaying states, so subscribing to all five
 * at init proves the loader init-order guarantee — whichever cell builds this
 * jar, and whenever the consumer loader invokes it, the trace observes the
 * full ordered sequence. The TCK phase scenario asserts {@link #observed()}
 * equals the enum's declaration order on every cell.
 *
 * <p><b>Invariants:</b> {@link #observed()} is strictly increasing in phase
 * order and never repeats a phase (the phase machine never re-enters).
 *
 * <p>When sub-01 Stage B lands the general event bus, a bus-subscription
 * exemplar joins this one; phase events remain the M0 event surface.
 */
public final class PhaseTrace {

    private static final List<EnginePhase> OBSERVED = new CopyOnWriteArrayList<>();

    private PhaseTrace() {
    }

    /** Subscribes one phase-change handler per phase. */
    public static void subscribe(VineEngine engine) {
        for (EnginePhase phase : EnginePhase.values()) {
            engine.onPhase(phase, change -> OBSERVED.add(change.entered()));
        }
    }

    /** The phases observed so far, in entry order. */
    public static List<EnginePhase> observed() {
        return List.copyOf(OBSERVED);
    }
}
