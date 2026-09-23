package dev.vineengine.vine.internal;

import dev.vineengine.vine.net.VineNet;

/**
 * Internal bridge from {@code VineNet} (vine-api) to vine-core's networking
 * service. NOT public API — implemented once by vine-core's engine object;
 * never implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess} rather than its own ServiceLoader
 * seam, so the engine's exactly-one-provider rule, boot ordering, and cached
 * boot failure apply to networking calls unchanged (same pattern as
 * {@link RegistryBackend}).
 */
public interface NetBackend {

    /** See {@code VineNet#get()}. */
    VineNet net();
}
