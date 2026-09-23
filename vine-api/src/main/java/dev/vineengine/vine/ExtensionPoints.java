package dev.vineengine.vine;

/**
 * The engine's extension-point registry (sub-01 Stage E): named, typed buckets
 * every later subsystem reuses instead of inventing its own registry. Points
 * are created once (idempotently) during boot; registration is open until
 * {@code REGISTRIES_FROZEN}, then the store freezes.
 */
public interface ExtensionPoints {

    /**
     * Returns the point with {@code id}, creating it if absent. Re-creating an
     * existing id with the same type returns it; a different type fails
     * explicitly (engine/driver skew, not a silent widening).
     */
    <E> ExtensionPoint<E> create(String id, Class<E> type);

    /**
     * Returns the point with {@code id} cast to {@code type}.
     *
     * @throws IllegalArgumentException if the id is unknown or holds another type
     */
    <E> ExtensionPoint<E> get(String id, Class<E> type);
}
