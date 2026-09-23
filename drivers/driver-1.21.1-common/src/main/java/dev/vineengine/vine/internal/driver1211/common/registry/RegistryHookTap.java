package dev.vineengine.vine.internal.driver1211.common.registry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import dev.vineengine.vine.hook.HookEvents;

/**
 * Static tap behind the {@code registryRegister} hook slot (sub-02 Stage B): the
 * per-loader materializers dispatch one event per structural entry they
 * register; the slot install subscribes the engine-bus sink here.
 *
 * <p>There is no native loader callback behind this hook — the engine's own
 * materialization is the source — so the "lazy native listener" degenerates to
 * a sink list. Zero subscribers ⇒ no event object is even allocated (Minimal
 * Footprint, §5.1).
 */
public final class RegistryHookTap {

    private static final List<Consumer<HookEvents.RegistryRegister>> SINKS = new CopyOnWriteArrayList<>();

    private RegistryHookTap() {
    }

    /** Subscribes {@code sink}; the returned handle unsubscribes (hook dormancy). */
    public static AutoCloseable subscribe(Consumer<HookEvents.RegistryRegister> sink) {
        SINKS.add(sink);
        return () -> SINKS.remove(sink);
    }

    /** Dispatches one materialized-entry event; a no-op without subscribers. */
    public static void dispatch(String registryId, String entryId) {
        if (SINKS.isEmpty()) {
            return;
        }
        HookEvents.RegistryRegister event = new HookEvents.RegistryRegister(registryId, entryId);
        for (Consumer<HookEvents.RegistryRegister> sink : SINKS) {
            sink.accept(event);
        }
    }
}
