package dev.vineengine.vine.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import dev.vineengine.vine.Cancellable;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.EventPriority;
import dev.vineengine.vine.Subscription;
import dev.vineengine.vine.VineEvent;
import dev.vineengine.vine.hook.HookSlot;

/**
 * The engine event bus (sub-01 Stage B, §2): per-type registrations ordered by
 * priority then registration (stable), synchronous {@code post}, and error
 * isolation — a throwing handler is caught, logged with its owner id, and the
 * bus continues. {@code cancel()} on a {@link Cancellable} event gates only the
 * not-yet-run handlers; nothing unwinds.
 *
 * <p>Posts snapshot the registration list, so handlers may subscribe or close
 * during dispatch without perturbing the post in flight. Posting is
 * reentrancy-safe (a handler may post again; each post owns its snapshot).
 */
final class EngineEventBus implements EventBus {

    private static final System.Logger LOG = System.getLogger("vine.events");

    /** Registrations per event class — guarded by {@link #lock}. */
    private final Map<Class<?>, List<Registration<?>>> byType = new HashMap<>();
    /** Hook slots per event class (sub-01 Stage C) — guarded by {@link #lock}. */
    private final Map<Class<?>, Slot> slots = new HashMap<>();
    private final Object lock = new Object();

    /**
     * Binds a hook slot to its event type (sub-01 Stage C): {@code install} runs
     * when the first handler for {@code type} subscribes, {@code uninstall} when
     * the last one closes. One slot per type; re-attaching replaces nothing.
     */
    void attachSlot(HookSlot slot, Runnable install, Runnable uninstall) {
        synchronized (lock) {
            slots.put(slot.type(), new Slot(slot, install, uninstall));
        }
    }

    /** Hook slots with their native source currently installed — the Minimal Footprint counter. */
    @Override
    public int activeHookInstalls() {
        synchronized (lock) {
            int active = 0;
            for (Slot slot : slots.values()) {
                if (slot.installed) {
                    active++;
                }
            }
            return active;
        }
    }

    @Override
    public <E extends VineEvent> Subscription subscribe(Class<E> type, Consumer<E> handler) {
        return subscribe(type, EventPriority.NORMAL, handler);
    }

    @Override
    public <E extends VineEvent> Subscription subscribe(Class<E> type, EventPriority priority,
            Consumer<E> handler) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(handler, "handler");
        Registration<E> registration = new Registration<>(type, priority, handler, ownerOf());
        Slot slotToInstall = null;
        synchronized (lock) {
            List<Registration<?>> list = byType.computeIfAbsent(type, k -> new ArrayList<>());
            boolean wasEmpty = list.isEmpty();
            // Insert before the first higher-priority entry; append after equals,
            // which keeps registration order stable within a priority.
            int i = 0;
            while (i < list.size() && list.get(i).priority.ordinal() <= priority.ordinal()) {
                i++;
            }
            list.add(i, registration);
            if (wasEmpty) {
                Slot slot = slots.get(type);
                if (slot != null && !slot.installed) {
                    slotToInstall = slot;
                }
            }
        }
        if (slotToInstall != null) {
            Slot slot = slotToInstall;
            // Native install outside the bus lock: driver installers may re-enter.
            try {
                slot.runInstall();
            } catch (RuntimeException e) {
                // No native source, no subscription: a seam hook must fail loudly,
                // never sit silently inert. Roll back and rethrow.
                registration.close();
                throw e;
            }
            synchronized (lock) {
                slot.installed = true;
            }
        }
        return registration;
    }

    @Override
    public <E extends VineEvent> E post(E event) {
        Objects.requireNonNull(event, "event");
        List<Registration<?>> snapshot;
        synchronized (lock) {
            List<Registration<?>> list = byType.get(event.getClass());
            if (list == null || list.isEmpty()) {
                return event;
            }
            snapshot = List.copyOf(list);
        }
        boolean cancellable = event instanceof Cancellable;
        for (Registration<?> registration : snapshot) {
            if (cancellable && ((Cancellable) event).isCancelled()) {
                return event; // cancel gates only the not-yet-run handlers
            }
            if (!registration.active) {
                continue;
            }
            registration.invoke(event);
        }
        return event;
    }

    /**
     * Owner id for error logs: the first stack frame outside the engine's own
     * packages — i.e. the consumer that subscribed (driver, mod, testmod).
     */
    private static String ownerOf() {
        for (StackTraceElement frame : new Throwable().getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("dev.vineengine.vine.internal.core")
                    || className.startsWith("dev.vineengine.vine.EventBus")
                    || className.startsWith("java.")
                    || className.startsWith("jdk.")) {
                continue;
            }
            return className + "#" + frame.getMethodName();
        }
        return "unknown";
    }

    /** One hook slot bound to an event type; {@code installed} is the native-source state. */
    private static final class Slot {

        final HookSlot slot;
        final Runnable install;
        final Runnable uninstall;
        boolean installed;

        Slot(HookSlot slot, Runnable install, Runnable uninstall) {
            this.slot = slot;
            this.install = install;
            this.uninstall = uninstall;
        }

        void runInstall() {
            install.run();
            LOG.log(System.Logger.Level.DEBUG,
                "[VINE] hook slot " + slot.id() + " installed (" + slot.source() + ")");
        }

        void runUninstall() {
            try {
                uninstall.run();
                LOG.log(System.Logger.Level.DEBUG, "[VINE] hook slot " + slot.id() + " uninstalled");
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[VINE] hook slot " + slot.id() + " uninstall failed", e);
            }
        }
    }

    private final class Registration<E extends VineEvent> implements Subscription {

        private final Class<E> type;
        private final EventPriority priority;
        private final Consumer<E> handler;
        private final String owner;
        private volatile boolean active = true;

        Registration(Class<E> type, EventPriority priority, Consumer<E> handler, String owner) {
            this.type = type;
            this.priority = priority;
            this.handler = handler;
            this.owner = owner;
        }

        @SuppressWarnings("unchecked")
        void invoke(VineEvent event) {
            try {
                handler.accept((E) event);
            } catch (RuntimeException e) {
                // Error isolation: the broken subscriber loses its event, the bus
                // and every later handler continue.
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] event handler for " + type.getSimpleName() + " (owner " + owner
                        + ") threw — continuing with remaining handlers", e);
            }
        }

        @Override
        public void close() {
            if (!active) {
                return;
            }
            active = false;
            Slot slotToUninstall = null;
            synchronized (lock) {
                List<Registration<?>> list = byType.get(type);
                if (list != null) {
                    list.remove(this);
                    if (list.isEmpty()) {
                        byType.remove(type);
                        Slot slot = slots.get(type);
                        if (slot != null && slot.installed) {
                            slot.installed = false;
                            slotToUninstall = slot;
                        }
                    }
                }
            }
            if (slotToUninstall != null) {
                // Native uninstall outside the bus lock: driver tear-down may re-enter.
                slotToUninstall.runUninstall();
            }
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
