package dev.vineengine.vine;

/**
 * Handle to an active engine subscription (phase listener, bus handler, hook).
 *
 * <p>Closing is the Minimal Footprint lever (§5.1): closing the last subscription
 * backed by a native loader hook may dorm that hook, so zero consumers means zero
 * native listeners — vanilla behavior.
 */
public interface Subscription extends AutoCloseable {

    /**
     * Ends the subscription. Idempotent; never throws.
     */
    @Override
    void close();

    /**
     * {@code true} while this subscription can still receive events. One-shot
     * subscriptions (e.g. {@link VineEngine#onPhase}) report {@code false} once fired.
     */
    boolean isActive();
}
