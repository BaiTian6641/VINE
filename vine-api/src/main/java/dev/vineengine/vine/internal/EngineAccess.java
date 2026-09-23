package dev.vineengine.vine.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import dev.vineengine.vine.VineEngine;

/**
 * Holds the engine singleton behind {@link VineEngine#get()}. NOT public API.
 *
 * <p>Exactly one {@link VineEngineProvider} must be on the classpath: zero means
 * {@code vine-core} is missing; two or more means conflicting engine jars. Both are
 * explicit boot failures — the engine never guesses.
 *
 * <p>Boot runs once; a boot failure is cached and rethrown so every caller sees the
 * same explicit failure instead of a half-booted engine. Calling {@code get()} from
 * inside boot (a driver touching the facade during {@code bootstrap}) is rejected —
 * drivers use {@code DriverContext} until boot completes.
 */
public final class EngineAccess {

    private static volatile VineEngine engine;
    private static volatile RuntimeException bootFailure;
    /** Guards recursion: a driver calling {@code VineEngine.get()} from inside bootstrap. */
    private static boolean booting;

    private EngineAccess() {
    }

    public static VineEngine get() {
        if (bootFailure != null) {
            throw bootFailure;
        }
        VineEngine current = engine;
        if (current == null) {
            synchronized (EngineAccess.class) {
                if (bootFailure != null) {
                    throw bootFailure;
                }
                current = engine;
                if (current == null) {
                    try {
                        current = boot();
                    } catch (RuntimeException e) {
                        bootFailure = e;
                        throw e;
                    }
                    engine = current;
                }
            }
        }
        return current;
    }

    private static VineEngine boot() {
        if (booting) {
            throw new IllegalStateException(
                "VineEngine.get() called during engine boot — drivers must use DriverContext during bootstrap");
        }
        booting = true;
        try {
            List<VineEngineProvider> providers = new ArrayList<>();
            for (VineEngineProvider provider : ServiceLoader.load(VineEngineProvider.class)) {
                providers.add(provider);
            }
            if (providers.isEmpty()) {
                throw new IllegalStateException(
                    "VINE engine core not found: no " + VineEngineProvider.class.getName()
                        + " service on the classpath (vine-core jar missing?)");
            }
            if (providers.size() > 1) {
                throw new IllegalStateException(
                    "VINE engine core conflict: " + providers.size() + " "
                        + VineEngineProvider.class.getName() + " providers on the classpath: " + providers);
            }
            return providers.get(0).create();
        } finally {
            booting = false;
        }
    }
}
