package dev.vineengine.vine.internal.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.Subscription;
import dev.vineengine.vine.VineEngine;

/**
 * Strictly ordered, replaying phase machine — the engine's heartbeat (sub-01 §2).
 *
 * <p>Every phase entry logs one canonical transition line,
 * {@code [VINE] phase <NAME>} on the {@code vine.boot} logger; the fifth line
 * ({@code [VINE] phase SERVER_UP}) is the engine-ready boot marker the TCK boot
 * smoke asserts (sub-21 Stage A). {@link #advanceTo} enters any intermediate phases
 * in order, so the five lines always appear exactly once and in ordinal order,
 * regardless of which loader events the driver anchored to.
 *
 * <p>Phases are <em>states</em>: a listener registered after its phase was entered
 * is replayed immediately ({@link #onPhase}), so loader init-order differences can
 * never strand a consumer.
 *
 * <p>Logging uses {@link System.Logger} to keep vine-core dependency-free; drivers
 * bridge it to the loader's Log4J setup.
 */
final class PhaseMachine {

    static final String LOG_NAME = "vine.boot";
    private static final System.Logger LOG = System.getLogger(LOG_NAME);

    private final EnumMap<EnginePhase, List<PhaseSubscription>> pending = new EnumMap<>(EnginePhase.class);
    private EnginePhase current;
    private Consumer<EnginePhase> transitionListener;

    /**
     * Installs the transition listener fired on every phase entry (the engine
     * routes {@code PhaseChange} onto the event bus through it, sub-01 Stage B).
     */
    synchronized void onTransition(Consumer<EnginePhase> listener) {
        this.transitionListener = listener;
    }

    /** Current phase, or {@code null} before {@link EnginePhase#VINE_BOOT} is entered. */
    synchronized EnginePhase current() {
        return current;
    }

    /**
     * Enters every phase up to and including {@code target}, in ordinal order.
     * Re-advancing to the current phase is a no-op; moving backwards throws.
     */
    synchronized void advanceTo(EnginePhase target) {
        Objects.requireNonNull(target, "target");
        if (current != null && target.ordinal() < current.ordinal()) {
            throw new IllegalStateException(
                "phase regression: cannot advance from " + current + " back to " + target);
        }
        EnginePhase[] phases = EnginePhase.values();
        while (current != target) {
            current = current == null ? phases[0] : phases[current.ordinal() + 1];
            enter(current);
        }
    }

    /**
     * One-shot subscription; replays immediately if {@code phase} is already entered.
     */
    synchronized Subscription onPhase(EnginePhase phase, Consumer<VineEngine.PhaseChange> handler) {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(handler, "handler");
        PhaseSubscription sub = new PhaseSubscription(phase, handler);
        if (current != null && current.ordinal() >= phase.ordinal()) {
            sub.fire();
            return sub;
        }
        pending.computeIfAbsent(phase, k -> new ArrayList<>()).add(sub);
        return sub;
    }

    private synchronized void cancel(EnginePhase phase, PhaseSubscription sub) {
        List<PhaseSubscription> subs = pending.get(phase);
        if (subs != null) {
            subs.remove(sub);
            if (subs.isEmpty()) {
                pending.remove(phase);
            }
        }
    }

    /** Caller holds the lock. */
    private void enter(EnginePhase phase) {
        LOG.log(System.Logger.Level.INFO, "[VINE] phase " + phase.name());
        Consumer<EnginePhase> listener = transitionListener;
        if (listener != null) {
            listener.accept(phase);
        }
        List<PhaseSubscription> subs = pending.remove(phase);
        if (subs != null) {
            for (PhaseSubscription sub : subs) {
                sub.fire();
            }
        }
    }

    private final class PhaseSubscription implements Subscription {
        private final EnginePhase phase;
        private final Consumer<VineEngine.PhaseChange> handler;
        private boolean active = true;

        PhaseSubscription(EnginePhase phase, Consumer<VineEngine.PhaseChange> handler) {
            this.phase = phase;
            this.handler = handler;
        }

        /** Caller holds the machine lock; handlers run in phase-entry order. */
        void fire() {
            synchronized (this) {
                if (!active) {
                    return;
                }
                active = false;
            }
            try {
                handler.accept(new VineEngine.PhaseChange(phase));
            } catch (RuntimeException e) {
                // A broken phase listener must not stall boot; Stage B's bus owns
                // full owner-tagged error isolation.
                LOG.log(System.Logger.Level.WARNING, "[VINE] phase listener threw during " + phase, e);
            }
        }

        @Override
        public void close() {
            synchronized (this) {
                if (!active) {
                    return;
                }
                active = false;
            }
            cancel(phase, this);
        }

        @Override
        public synchronized boolean isActive() {
            return active;
        }
    }
}
