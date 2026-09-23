package dev.vineengine.vine.capability;

/**
 * Creates the capability instance for one target (sub-04 §2). Called lazily
 * on the first {@code find} for a (target, type) pair; the returned instance
 * is then cached per target.
 *
 * @param <T> the consumer-visible capability interface
 */
@FunctionalInterface
public interface CapabilityProvider<T> {

    /** Creates (or restores) the instance for {@code target}. */
    T create(CapabilityTarget target);
}
