package dev.vineengine.vine;

import dev.vineengine.vine.internal.ConfigBackend;
import dev.vineengine.vine.internal.EngineAccess;

/**
 * Typed engine configuration (sub-01 Stage E, plan §11.4-resolved): reads
 * {@code config/vine/engine.toml} relative to the server working directory,
 * falling back to provided defaults for every missing key. The file is read
 * lazily on first access; {@link #reload()} re-reads it and fires reload
 * listeners.
 *
 * <p>Keys are {@code section.key} strings over a minimal TOML subset
 * ({@code [section]} headers, {@code key = value} pairs, {@code #} comments) —
 * the reader is engine-internal and swappable; the API never changes.
 */
public final class VineConfig {

    private VineConfig() {
    }

    /** String value for {@code key}, or {@code defaultValue} when absent. */
    public static String getString(String key, String defaultValue) {
        return backend().getString(key, defaultValue);
    }

    /** Integer value for {@code key} (long-parse + range-check), or the default. */
    public static int getInt(String key, int defaultValue) {
        return backend().getInt(key, defaultValue);
    }

    /** Long value for {@code key}, or the default. */
    public static long getLong(String key, long defaultValue) {
        return backend().getLong(key, defaultValue);
    }

    /** Boolean value for {@code key} ({@code true}/{@code false}), or the default. */
    public static boolean getBoolean(String key, boolean defaultValue) {
        return backend().getBoolean(key, defaultValue);
    }

    /** Re-reads the config file and fires every registered reload listener. */
    public static void reload() {
        backend().reload();
    }

    /** Registers {@code listener} to run after each successful reload. */
    public static void onReload(Runnable listener) {
        backend().onReload(listener);
    }

    private static ConfigBackend backend() {
        if (EngineAccess.get() instanceof ConfigBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "engine core does not implement the config backend — vine-core version mismatch");
    }
}
