package dev.vineengine.vine.internal.driver1211.common;

import dev.vineengine.vine.internal.driver1211.common.events.HookBus;
import dev.vineengine.vine.internal.spi.NetDriver;

/**
 * Process-lifetime handle to the bound driver's runtime pieces, installed during
 * {@code bootstrap}. Exists so engine-side consumers (TCK, testmod) can reach the
 * driver-internal collector and the network inbound seam while sub-01's bus is
 * pending; dissolves into {@code VineEngine.events()} / the engine facade once
 * the corresponding surfaces land (documented seam).
 *
 * <p>The {@link #inboundSink()} accessor is the TCK scenario runner's injection
 * point (sub-21 Stage B): on a headless dedicated server, scenarios drive the
 * bytes-only contract by delivering hand-encoded frames with the appropriate
 * {@code Endpoint} — the engine path (decode, dispatch, handler) is real, only
 * wire movement is simulated.
 */
public final class DriverRuntime {

    private static volatile HookBus hooks;
    private static volatile NetDriver net;
    private static volatile NetDriver.InboundSink inboundSink;

    private DriverRuntime() {
    }

    /** Called once by the driver's {@code bootstrap}. */
    public static void install(HookBus hookBus) {
        hooks = hookBus;
    }

    /** Called once by the driver's network transport bind. */
    public static void installNet(NetDriver driver, NetDriver.InboundSink sink) {
        net = driver;
        inboundSink = sink;
    }

    /**
     * The driver-internal hook collector.
     *
     * @throws IllegalStateException before engine boot binds the driver
     */
    public static HookBus hooks() {
        HookBus current = hooks;
        if (current == null) {
            throw new IllegalStateException("VINE driver not booted yet — hooks() is valid after bootstrap");
        }
        return current;
    }

    /** The bound network transport.
     *
     * @throws IllegalStateException before the transport bind
     */
    public static NetDriver net() {
        NetDriver current = net;
        if (current == null) {
            throw new IllegalStateException("VINE driver transport not bound yet — net() is valid after bootstrap");
        }
        return current;
    }

    /**
     * The engine's inbound sink — the only path received payload bytes take into
     * the engine, and the TCK's frame-injection point on a headless server.
     *
     * @throws IllegalStateException before the transport bind
     */
    public static NetDriver.InboundSink inboundSink() {
        NetDriver.InboundSink current = inboundSink;
        if (current == null) {
            throw new IllegalStateException("VINE driver transport not bound yet — inboundSink() is valid after bootstrap");
        }
        return current;
    }
}
