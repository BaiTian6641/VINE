package dev.vineengine.vine.internal.spi;

/**
 * Driver-supplied per-world store for engine-owned data (sub-14 Stage B,
 * generalized in sub-02 Stage D): the session store and the persistent
 * {@code VineId}<->int map live here, so they outlive the process without the
 * engine knowing where the world keeps its files.
 *
 * <p>Keys are engine-internal plain strings ({@code sessions}, {@code id_map});
 * the driver maps them onto files under the world directory. Both methods run
 * on the server thread; failures must throw (the engine logs and continues — a
 * broken store never crashes world save).
 */
public interface WorldStoreSpi {

    /** The persisted blob for {@code key}, or {@code null} when nothing was stored yet. */
    byte[] load(String key);

    /** Durably stores {@code blob} under {@code key}, replacing any previous content. */
    void save(String key, byte[] blob);
}
