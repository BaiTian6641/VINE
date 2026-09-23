package dev.vineengine.vine.internal;

import java.util.Optional;

import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;

/**
 * Internal bridge from {@code VineRegistries} (vine-api) to vine-core's
 * {@code DescriptorStore}. NOT public API — implemented once by vine-core's
 * engine object; never implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess} rather than its own ServiceLoader
 * seam, so the engine's exactly-one-provider rule, boot ordering, and cached
 * boot failure apply to registry calls unchanged.
 */
public interface RegistryBackend {

    /** See {@code VineRegistries#defineType}. */
    <D> void defineType(DescriptorType<D> type);

    /** See {@code VineRegistries#register}. */
    <D> Holder<D> register(DescriptorType<D> type, VineId id, D data);

    /** See {@code VineRegistries#get}. */
    <D> Optional<Holder<D>> get(DescriptorType<D> type, VineId id);
}
