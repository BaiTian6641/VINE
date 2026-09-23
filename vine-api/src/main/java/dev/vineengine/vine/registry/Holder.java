package dev.vineengine.vine.registry;

/**
 * A registered descriptor: its id, its value, and its slot in the engine's
 * persistent ID map (sub-02 §2).
 *
 * <p><b>Invariant:</b> a {@link DescriptorClass#DESIGN} holder is volatile — it
 * is rebound on world load and {@code /reload}; never cache one across either
 * (sub-02 §4). Structural holders are stable from
 * {@link dev.vineengine.vine.EnginePhase#REGISTRIES_FROZEN} for the JVM session.
 */
public interface Holder<D> {

    /** The id this descriptor was registered under. */
    VineId id();

    /** The descriptor data, exactly as authored (Java or decoded JSON). */
    D value();

    /**
     * Slot in the engine-owned persistent {@code VineId}&harr;int map (§5.2).
     * Until that map lands (sub-02 Stage D), assignment is per-type registration
     * order within the JVM session — stable after {@code REGISTRIES_FROZEN}, not
     * yet across sessions.
     */
    int runtimeId();
}
