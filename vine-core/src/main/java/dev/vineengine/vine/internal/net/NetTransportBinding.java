package dev.vineengine.vine.internal.net;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The core-side binding point for the process's single {@link NetTransport}
 * (sub-05 §2). A driver binds its transport once, after its loader networking
 * hooks are wired, and receives the engine's {@link NetTransport.InboundSink}
 * in return — the only path inbound payload bytes take into vine-core.
 *
 * <p><b>Seam status (sub-05 Stage A):</b> pending the vine-spi {@code NetDriver}
 * addition, drivers (and the loopback harness) call {@link #bind} directly.
 * Once {@code NetDriver} lands, vine-core bridges the SPI to this binding and
 * the direct call disappears from drivers.
 *
 * <p><b>Invariants:</b> exactly one transport per process — a second bind is a
 * conflicting-driver bug and throws. Binding replays to late listeners (same
 * replaying-state rule as the phase machine), so bind order relative to the
 * core service's construction can never strand either side.
 */
public final class NetTransportBinding {

    private static volatile NetTransport transport;
    private static volatile NetTransport.InboundSink engineSink;
    private static final List<Consumer<NetTransport>> listeners = new CopyOnWriteArrayList<>();

    private NetTransportBinding() {
    }

    /**
     * Installs the engine's inbound sink. Called once by vine-core's networking
     * service at construction — before any driver can bind.
     */
    public static void engineSink(NetTransport.InboundSink sink) {
        if (engineSink != null) {
            throw new IllegalStateException("engine inbound sink already installed");
        }
        engineSink = Objects.requireNonNull(sink, "sink");
    }

    /**
     * Binds the process transport and returns the engine's inbound sink the
     * driver delivers received payload bytes to.
     *
     * @throws IllegalStateException on a second bind, or if the engine's
     *         networking service has not installed its sink (driver binding
     *         before engine boot — a driver bug, reported explicitly)
     */
    public static synchronized NetTransport.InboundSink bind(NetTransport candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (transport != null) {
            throw new IllegalStateException("NetTransport already bound: " + transport.getClass().getName()
                + " (conflicting second: " + candidate.getClass().getName() + ")");
        }
        NetTransport.InboundSink sink = engineSink;
        if (sink == null) {
            throw new IllegalStateException(
                "driver bound a NetTransport before the engine installed its inbound sink");
        }
        transport = candidate;
        for (Consumer<NetTransport> listener : listeners) {
            listener.accept(candidate);
        }
        return sink;
    }

    /**
     * Registers {@code listener} for the transport binding; replays immediately
     * if a transport is already bound.
     */
    public static void onBind(Consumer<NetTransport> listener) {
        NetTransport current = transport;
        if (current != null) {
            listener.accept(current);
            return;
        }
        listeners.add(listener);
        // Re-check: a bind racing between the read and the add must not be missed.
        current = transport;
        if (current != null && listeners.remove(listener)) {
            listener.accept(current);
        }
    }
}
