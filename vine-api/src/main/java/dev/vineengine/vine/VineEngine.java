package dev.vineengine.vine;

import dev.vineengine.vine.internal.EngineAccess;

import java.util.function.Consumer;

/**
 * The engine facade — the single entry point every consumer uses.
 *
 * <p>The instance is a process-wide singleton backed by {@code vine-core}, resolved
 * through {@link java.util.ServiceLoader}: exactly one
 * {@code dev.vineengine.vine.internal.VineEngineProvider} must be on the classpath
 * or {@link #get()} fails explicitly.
 *
 * <p>Phase subscription semantics: phases are replaying <em>states</em>, so a
 * handler registered after a phase was entered still receives that phase's
 * {@link PhaseChange} immediately — loader init-order differences can never strand
 * a consumer (sub-01 §4).
 */
public interface VineEngine {

    /**
     * Returns the engine singleton, booting it on first call. Boot failure (no core
     * provider, conflicting providers, or a driver refusing the runtime cell) is
     * explicit: this method throws, and keeps throwing the cached failure.
     */
    static VineEngine get() {
        return EngineAccess.get();
    }

    /**
     * The current boot phase. Monotonic: never decreases over the process lifetime.
     */
    EnginePhase phase();

    /**
     * One-shot subscription to a phase entry. If {@code phase} is already entered,
     * {@code handler} is invoked immediately (replay) and the returned subscription
     * is inactive; otherwise it fires exactly once when the phase is entered.
     */
    Subscription onPhase(EnginePhase phase, Consumer<PhaseChange> handler);

    /**
     * Fired when the engine enters a phase. Routed through the engine's phase
     * machine (and, from Stage B on, the event bus).
     */
    record PhaseChange(EnginePhase entered) implements VineEvent {
    }
}
