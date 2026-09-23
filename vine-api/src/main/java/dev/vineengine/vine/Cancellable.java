package dev.vineengine.vine;

/**
 * An event a handler may veto (sub-01 §2). {@link EventBus#post} stops running
 * <em>not-yet-run</em> handlers once {@link #cancel()} was called; handlers that
 * already ran are unaffected, and nothing unwinds — the event is still returned
 * so the caller can inspect {@link #isCancelled()}.
 */
public interface Cancellable extends VineEvent {

    /** {@code true} once a handler called {@link #cancel()}. */
    boolean isCancelled();

    /** Vetoes the event; subsequent handlers of this post are skipped. */
    void cancel();
}
