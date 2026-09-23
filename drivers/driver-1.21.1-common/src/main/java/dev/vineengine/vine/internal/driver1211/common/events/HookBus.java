package dev.vineengine.vine.internal.driver1211.common.events;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.Subscription;

/**
 * Driver-internal event collector pending sub-01's Stage B engine bus: per-hook
 * lazy install of native loader listeners (first subscriber installs, last close
 * uninstalls — Minimal Footprint §5.1), priority-free dispatch with error
 * isolation, and a native-listener counter for the zero-consumer TCK smoke.
 *
 * <p>Each driver hands in its loader's installers at bootstrap
 * ({@code driver1211.<loader>.events}); this class is loader-neutral mechanics
 * only. Hooks whose native source is a documented seam ({@link VineHook}) have no
 * installer and fail subscriptions explicitly. Content-driven hooks
 * ({@code PACKET_RECEIVE}, {@code COMMAND_EXECUTE}) install no listener of their
 * own — their native plumbing exists for the engine regardless — the installer
 * just captures the sink the live path posts to while subscribed.
 *
 * <p>Seam to sub-01: when {@code DriverContext.bus()} / {@code installHook(...)}
 * land, subscriptions here are re-rooted onto the engine bus and this collector
 * dissolves into the installers. Until then the TCK/testmod subscribe here via
 * {@code DriverRuntime.hooks()}.
 */
public final class HookBus {

    /** Installs the native listener(s) backing one hook; the returned handle uninstalls them. */
    @FunctionalInterface
    public interface Installer {
        AutoCloseable install(Consumer<HookEvent> sink);
    }

    private static final Logger LOG = LoggerFactory.getLogger(HookBus.class);

    private final Map<VineHook, Installer> installers;
    private final Map<VineHook, List<Consumer<HookEvent>>> handlers = new EnumMap<>(VineHook.class);
    private final Map<VineHook, AutoCloseable> installed = new EnumMap<>(VineHook.class);

    public HookBus(Map<VineHook, Installer> installers) {
        this.installers = Map.copyOf(Objects.requireNonNull(installers, "installers"));
    }

    /**
     * Subscribes {@code handler} to {@code hook}, installing the native listener on
     * first use. Throws when the hook's native source is a documented seam.
     */
    public synchronized Subscription subscribe(VineHook hook, Consumer<HookEvent> handler) {
        Objects.requireNonNull(hook, "hook");
        Objects.requireNonNull(handler, "handler");
        Installer installer = installers.get(hook);
        if (installer == null) {
            throw new IllegalStateException("hook " + hook + " has no native source yet on this driver"
                + " (documented seam — see VineHook javadoc)");
        }
        List<Consumer<HookEvent>> list = handlers.computeIfAbsent(hook, h -> new ArrayList<>());
        if (list.isEmpty()) {
            installed.put(hook, installer.install(this::dispatch));
            LOG.debug("[VINE] hook {} native listener installed ({} active)", hook, installed.size());
        }
        list.add(handler);
        return new HookSubscription(hook, handler);
    }

    /** Native listeners currently installed — the Minimal Footprint debug counter. */
    public synchronized int activeNativeListeners() {
        return installed.size();
    }

    private void dispatch(HookEvent event) {
        List<Consumer<HookEvent>> list;
        synchronized (this) {
            list = List.copyOf(handlers.getOrDefault(event.hook(), List.of()));
        }
        for (Consumer<HookEvent> handler : list) {
            try {
                handler.accept(event);
            } catch (RuntimeException e) {
                // Error isolation: one throwing consumer never stops the others.
                LOG.error("[VINE] hook {} consumer threw", event.hook(), e);
            }
        }
    }

    private synchronized void unsubscribe(VineHook hook, Consumer<HookEvent> handler) {
        List<Consumer<HookEvent>> list = handlers.get(hook);
        if (list == null || !list.remove(handler)) {
            return;
        }
        if (list.isEmpty()) {
            handlers.remove(hook);
            AutoCloseable nativeHandle = installed.remove(hook);
            if (nativeHandle != null) {
                try {
                    nativeHandle.close();
                } catch (Exception e) {
                    LOG.warn("[VINE] hook {} native listener uninstall failed", hook, e);
                }
            }
            LOG.debug("[VINE] hook {} native listener uninstalled ({} active)", hook, installed.size());
        }
    }

    private final class HookSubscription implements Subscription {
        private final VineHook hook;
        private final Consumer<HookEvent> handler;
        private boolean active = true;

        private HookSubscription(VineHook hook, Consumer<HookEvent> handler) {
            this.hook = hook;
            this.handler = handler;
        }

        @Override
        public synchronized void close() {
            if (active) {
                active = false;
                unsubscribe(hook, handler);
            }
        }

        @Override
        public synchronized boolean isActive() {
            return active;
        }
    }
}
