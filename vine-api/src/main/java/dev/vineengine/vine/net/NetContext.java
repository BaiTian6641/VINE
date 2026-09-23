package dev.vineengine.vine.net;

import dev.vineengine.vine.VinePlayer;

/**
 * Per-message context handed to a {@link MessageHandler} (sub-05 §2).
 *
 * <p><b>Invariants:</b> {@link #enqueue} is the ONLY sanctioned thread hop —
 * handlers are dispatched on the receiving side's main thread, and any follow-up
 * work must ride {@link #enqueue} rather than executor state captured by the
 * consumer (loader handler threads differ per cell; §4). Never trust
 * {@link #sender()} beyond session proof: it identifies the connection the
 * payload arrived on, nothing more.
 */
public interface NetContext {

    /** The connection the payload arrived from. */
    VinePlayer sender();

    /** Runs {@code task} on the receiving side's main thread. */
    void enqueue(Runnable task);
}
