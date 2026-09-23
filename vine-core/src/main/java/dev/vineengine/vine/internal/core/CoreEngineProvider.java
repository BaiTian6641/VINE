package dev.vineengine.vine.internal.core;

import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.internal.VineEngineProvider;

/**
 * ServiceLoader entry point connecting {@link VineEngine#get()} to vine-core —
 * registered in {@code META-INF/services/dev.vineengine.vine.internal.VineEngineProvider}.
 * NOT public API.
 */
public final class CoreEngineProvider implements VineEngineProvider {

    public CoreEngineProvider() {
    }

    @Override
    public VineEngine create() {
        return new VineEngineImpl();
    }
}
