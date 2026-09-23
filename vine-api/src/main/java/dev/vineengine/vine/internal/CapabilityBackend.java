package dev.vineengine.vine.internal;

import java.util.Optional;

import dev.vineengine.vine.capability.CapabilityProvider;
import dev.vineengine.vine.capability.CapabilityScope;
import dev.vineengine.vine.capability.CapabilityTarget;
import dev.vineengine.vine.capability.CapabilityType;
import dev.vineengine.vine.registry.VineId;

/**
 * Internal bridge from {@code VineCapabilities} (vine-api) to vine-core's
 * capability store. NOT public API — implemented once by vine-core's engine
 * object; never implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess} so the engine's exactly-one-provider
 * rule, boot ordering, and cached boot failure apply unchanged (same pattern
 * as {@link RegistryBackend}).
 */
public interface CapabilityBackend {

    /** See {@code VineCapabilities#register}. */
    <T> CapabilityType<T> register(CapabilityType<T> type);

    /** See {@code VineCapabilities#attach}. */
    <T> void attach(CapabilityType<T> type, CapabilityScope scope, CapabilityProvider<T> provider);

    /** See {@code VineCapabilities#find}. */
    <T> Optional<T> find(CapabilityType<T> type, CapabilityTarget target);

    /** See {@code VineCapabilities#findForeign}. */
    <T> Optional<T> findForeign(VineId nativeId, Class<T> apiClass, CapabilityTarget target);

    /** See {@code VineCapabilities#flush}. */
    void flush(dev.vineengine.vine.capability.CapabilityTarget target);

    /** See {@code VineCapabilities#invalidate}. */
    void invalidate(CapabilityTarget target);

    /** See {@code VineCapabilities#applyClone}. */
    <T> void applyClone(CapabilityType<T> type, CapabilityTarget oldTarget,
            CapabilityTarget newTarget);
}
