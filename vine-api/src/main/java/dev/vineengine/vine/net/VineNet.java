package dev.vineengine.vine.net;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.NetBackend;

/**
 * The engine networking facade (sub-05 §2): channel registration, readiness
 * probing, and — through {@link Channel} — message send/receive. Consumers see
 * only this package; payload registration, wire framing, and loader networking
 * live in vine-core and the per-cell drivers.
 *
 * <p>Backed by vine-core through the same {@code ServiceLoader} boot seam as
 * {@code VineEngine.get()}, so the exactly-one-provider rule and cached boot
 * failure apply unchanged. With no driver bound (inert engine, Minimal
 * Footprint) channels still register and sends are logged no-ops.
 */
public interface VineNet {

    /**
     * Returns the networking facade, booting the engine on first call (same
     * seam and failure semantics as {@code VineEngine.get()}).
     */
    static VineNet get() {
        if (EngineAccess.get() instanceof NetBackend backend) {
            return backend.net();
        }
        throw new IllegalStateException(
            "vine-core engine does not provide networking services — mismatched vine-api/vine-core jars");
    }

    /**
     * Registers (idempotently per equal spec) and returns the channel for
     * {@code spec}. An equal id with a conflicting spec throws.
     *
     * @throws IllegalStateException if registration is frozen
     *         ({@code REGISTRIES_FROZEN} entered)
     */
    Channel channel(ChannelSpec spec);

    /**
     * Whether the engine handshake has completed for {@code player}'s
     * connection. Stage A semantics (pre-handshake, stage C): reports transport
     * readiness — a bound driver transport that sees a live connection.
     */
    boolean isReady(VinePlayer player);
}
