package dev.vineengine.vine.internal.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import dev.vineengine.vine.ExtensionPoint;
import dev.vineengine.vine.ExtensionPoints;

/**
 * vine-core's extension-point store (sub-01 Stage E): id-keyed, typed, frozen
 * at {@code REGISTRIES_FROZEN}. Single-threaded by contract — creation and
 * registration happen on the boot thread before the freeze, exactly like the
 * descriptor store; the freeze makes the maps safe to read from any thread
 * afterwards.
 */
final class ExtensionPointsImpl implements ExtensionPoints {

    private final Map<String, Point<?>> points = new HashMap<>();
    private volatile boolean frozen;

    @Override
    public synchronized <E> ExtensionPoint<E> create(String id, Class<E> type) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Point<?> existing = points.get(id);
        if (existing != null) {
            if (existing.type != type) {
                throw new IllegalArgumentException("extension point '" + id + "' already exists as "
                    + existing.type.getName() + " (requested " + type.getName() + ")");
            }
            return existing.as(type);
        }
        Point<E> point = new Point<>(id, type);
        points.put(id, point);
        return point;
    }

    @Override
    public synchronized <E> ExtensionPoint<E> get(String id, Class<E> type) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Point<?> point = points.get(id);
        if (point == null) {
            throw new IllegalArgumentException("unknown extension point '" + id + "'");
        }
        if (point.type != type) {
            throw new IllegalArgumentException("extension point '" + id + "' holds "
                + point.type.getName() + ", not " + type.getName());
        }
        return point.as(type);
    }

    /** Freezes every point: later registrations fail explicitly (sub-01 §2). */
    synchronized void freeze() {
        frozen = true;
    }

    private final class Point<E> implements ExtensionPoint<E> {

        private final String id;
        private final Class<E> type;
        private final List<E> extensions = new ArrayList<>();

        Point(String id, Class<E> type) {
            this.id = id;
            this.type = type;
        }

        @SuppressWarnings("unchecked")
        <T> ExtensionPoint<T> as(Class<T> requested) {
            return (ExtensionPoint<T>) this;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Class<E> type() {
            return type;
        }

        @Override
        public void register(E extension) {
            Objects.requireNonNull(extension, "extension");
            synchronized (ExtensionPointsImpl.this) {
                if (frozen) {
                    throw new IllegalStateException(
                        "extension point '" + id + "' is frozen (registration closed at REGISTRIES_FROZEN)");
                }
                extensions.add(extension);
            }
        }

        @Override
        public synchronized List<E> all() {
            return List.copyOf(extensions);
        }

        @Override
        public synchronized Stream<E> stream() {
            return List.copyOf(extensions).stream();
        }
    }
}
