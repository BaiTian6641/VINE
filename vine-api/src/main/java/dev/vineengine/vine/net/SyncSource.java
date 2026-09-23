package dev.vineengine.vine.net;

import dev.vineengine.vine.VinePlayer;

/**
 * The producer behind {@link Channel#sync} (sub-05 Stage E): on player join —
 * after the handshake, before any consumer traffic — the engine asks for the
 * snapshot and delivers it to that player. Sources run in registration order,
 * so a consumer's dependent snapshots arrive in the order it declared them.
 *
 * @param <P> the snapshot payload type, registered as an S2C message
 */
@FunctionalInterface
public interface SyncSource<P> {

    /** The snapshot for {@code player}. Called on the thread the join arrived on. */
    P snapshot(VinePlayer player);
}
