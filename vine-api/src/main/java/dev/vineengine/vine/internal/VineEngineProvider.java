package dev.vineengine.vine.internal;

import dev.vineengine.vine.VineEngine;

/**
 * ServiceLoader seam between the API jar and the engine core. NOT public API —
 * implemented once by {@code vine-core}
 * ({@code dev.vineengine.vine.internal.core.CoreEngineProvider}, registered in
 * {@code META-INF/services}); never implemented or referenced by consumers.
 */
public interface VineEngineProvider {

    /**
     * Boots and returns the engine core. Called at most once per process by
     * {@link dev.vineengine.vine.internal.EngineAccess}.
     */
    VineEngine create();
}
