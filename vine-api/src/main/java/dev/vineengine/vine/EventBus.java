package dev.vineengine.vine;

import java.util.function.Consumer;

/**
 * The engine event bus (sub-01 §2): synchronous, typed event dispatch inside
 * the engine's prime-invariant domain (no Minecraft or loader types cross it).
 *
 * <p>Semantics:
 * <ul>
 *   <li>Handlers for one event type run in {@link EventPriority} order, and in
 *   registration order within a priority.</li>
 *   <li>A throwing handler is isolated: it is logged with the subscriber's
 *   owner id and the bus continues with the remaining handlers.</li>
 *   <li>For {@link Cancellable} events, {@code cancel()} gates only the
 *   not-yet-run handlers.</li>
 *   <li>Subscribing or closing during a {@code post} never affects the post in
 *   flight (handlers are snapshotted per post).</li>
 * </ul>
 */
public interface EventBus {

    /** Subscribes at {@link EventPriority#NORMAL}. */
    <E extends VineEvent> Subscription subscribe(Class<E> type, Consumer<E> handler);

    /** Subscribes at an explicit priority. */
    <E extends VineEvent> Subscription subscribe(Class<E> type, EventPriority priority, Consumer<E> handler);

    /**
     * Posts {@code event} to every handler registered for its exact class,
     * synchronously on the calling thread. Returns {@code event} for cancel
     * inspection.
     */
    <E extends VineEvent> E post(E event);

    /**
     * Debug counter for the Minimal Footprint rule (§5.1): how many hook slots
     * currently have their native source installed. Zero at boot with zero
     * consumers — the TCK's zero-consumer assertion.
     */
    int activeHookInstalls();
}
