package dev.vineengine.vine.internal;

/**
 * Internal bridge from {@code VineConfig} (vine-api) to vine-core's config
 * service. NOT public API — implemented once by vine-core's engine object.
 *
 * <p>Resolved through {@link EngineAccess} so the engine's exactly-one-provider
 * rule, boot ordering, and cached boot failure apply unchanged (same pattern
 * as {@link RegistryBackend}).
 */
public interface ConfigBackend {

    /** See {@code VineConfig#getString}. */
    String getString(String key, String defaultValue);

    /** See {@code VineConfig#getInt}. */
    int getInt(String key, int defaultValue);

    /** See {@code VineConfig#getLong}. */
    long getLong(String key, long defaultValue);

    /** See {@code VineConfig#getBoolean}. */
    boolean getBoolean(String key, boolean defaultValue);

    /** See {@code VineConfig#reload}. */
    void reload();

    /** See {@code VineConfig#onReload}. */
    void onReload(Runnable listener);
}
