package dev.vineengine.vine.internal.capability;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.vineengine.vine.capability.CapabilityType;
import dev.vineengine.vine.registry.VineId;

/**
 * The engine's capability type table (sub-04 §2 internals). Registration
 * closes at {@code REGISTRIES_FROZEN} — late registration throws, never
 * silently drops; reads stay open forever.
 */
final class CapabilityRegistry {

    private final Map<VineId, CapabilityType<?>> types = new LinkedHashMap<>();
    private boolean frozen;

    synchronized boolean frozen() {
        return frozen;
    }

    synchronized void freeze() {
        frozen = true;
    }

    synchronized <T> CapabilityType<T> register(CapabilityType<T> type) {
        checkWritable("register capability type " + type.id());
        if (types.containsKey(type.id())) {
            throw new IllegalStateException("capability type " + type.id() + " is already registered");
        }
        types.put(type.id(), type);
        return type;
    }

    synchronized CapabilityType<?> get(VineId id) {
        return types.get(id);
    }

    private void checkWritable(String action) {
        if (frozen) {
            throw new IllegalStateException("cannot " + action
                + " — capability registration closed at REGISTRIES_FROZEN");
        }
    }
}
