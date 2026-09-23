package dev.vineengine.vine;

import java.util.List;
import java.util.stream.Stream;

/**
 * A named, typed extension point (sub-01 §2): every later engine extension
 * (capabilities, quest types, …) reuses this. Registration is legal until the
 * store freezes at {@code REGISTRIES_FROZEN}; afterwards it fails explicitly.
 */
public interface ExtensionPoint<E> {

    /** The point's id (as passed to {@link ExtensionPoints#create}). */
    String id();

    /** The element type acceptors receive. */
    Class<E> type();

    /**
     * Registers {@code extension} at this point.
     *
     * @throws IllegalStateException if the store is frozen
     * @throws IllegalArgumentException if the point holds a different type
     */
    void register(E extension);

    /** Snapshot of everything registered, in registration order. */
    List<E> all();

    /** Streaming view of {@link #all()}. */
    Stream<E> stream();
}
