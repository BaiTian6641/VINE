package dev.vineengine.vine.internal.spi;

import java.util.List;

import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.Holder;

/**
 * Read-only structural slice of vine-core's {@code DescriptorStore}, handed to
 * drivers for materialization (sub-02 §2). Version-free by construction: only
 * vine-api types cross this seam (Prime Invariant).
 *
 * <p><b>Invariant:</b> implementations return an immutable snapshot per call —
 * the driver may iterate it at its loader's registration moment without locking
 * against late registrations or the {@code REGISTRIES_FROZEN} transition.
 */
public interface StructuralRegistryView {

    /**
     * Every defined {@code STRUCTURAL} descriptor type with its entries, ordered
     * by {@code registryId} (deterministic materialization order on every cell).
     */
    List<StructuralType> types();

    /** One structural descriptor type plus its registered entries. */
    interface StructuralType {

        /** The type token; carries the {@code Codec}, class, and registryId. */
        DescriptorType<?> type();

        /** Registered holders, ordered by {@code VineId} (the store's sorted order). */
        List<? extends Holder<?>> entries();
    }
}
