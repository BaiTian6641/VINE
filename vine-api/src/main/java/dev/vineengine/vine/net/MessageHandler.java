package dev.vineengine.vine.net;

/**
 * Consumer handler for one registered message (sub-05 §2). Invoked on the
 * receiving side's main thread (the engine re-dispatches off the loader's
 * network thread; §4) after the payload has been fully decoded and — from
 * stage D on — passed the validator chain.
 */
@FunctionalInterface
public interface MessageHandler<P> {

    /**
     * Handles one decoded payload. Any state mutation off the current thread
     * must be scheduled via {@link NetContext#enqueue}.
     */
    void handle(P payload, NetContext context);
}
