package dev.vineengine.vine.internal.spi;

/**
 * Driver-supplied persistence store for engine sessions (sub-14 Stage B). The
 * driver mounts one on world load and flushes it on world save, so session
 * state outlives the process without the engine knowing where it lives.
 *
 * <p>Both methods run on the server thread; failures must throw (the engine
 * logs and continues — a broken store never crashes world save).
 */
public interface SessionPersistenceSpi {

    /** The persisted blob, or {@code null} when nothing was stored yet. */
    byte[] load();

    /** Durably stores {@code blob}, replacing any previous content. */
    void save(byte[] blob);
}
