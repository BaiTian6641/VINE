package dev.vineengine.vine.internal.driver1211.common.registry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.vineengine.vine.internal.driver1211.common.events.HookEvent;

/**
 * Static tap behind the {@code REGISTRY_REGISTER} hook (sub-02 Stage B): the
 * per-loader materializers dispatch one event per structural entry they
 * register; the hook installer subscribes the {@code HookBus} sink here.
 *
 * <p>There is no native loader callback behind this hook — the engine's own
 * materialization is the source — so the "lazy native listener" degenerates to
 * a sink list. Zero subscribers ⇒ no event object is even allocated (Minimal
 * Footprint, §5.1).
 */
public final class RegistryHookTap {

    private static final List<Consumer<HookEvent>> SINKS = new CopyOnWriteArrayList<>();

    private RegistryHookTap() {
    }

    /** Subscribes {@code sink}; the returned handle unsubscribes (hook dormancy). */
    public static AutoCloseable subscribe(Consumer<HookEvent> sink) {
        SINKS.add(sink);
        return () -> SINKS.remove(sink);
    }

    /** Dispatches one materialized-entry event; a no-op without subscribers. */
    public static void dispatch(String registryId, String entryId) {
        if (SINKS.isEmpty()) {
            return;
        }
        HookEvent event = new HookEvent.RegistryRegister(registryId, entryId);
        for (Consumer<HookEvent> sink : SINKS) {
            sink.accept(event);
        }
    }
}
