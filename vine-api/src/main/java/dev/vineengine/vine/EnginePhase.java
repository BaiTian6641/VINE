package dev.vineengine.vine;

/**
 * Normalized engine boot phases, independent of any loader's lifecycle (§5.8).
 *
 * <p>Phases are <em>states</em>, not events: {@link VineEngine#onPhase} replays an
 * already-entered phase to late subscribers, so loader init-order differences (NF
 * mod-construction vs. Fabric {@code onInitialize}) can never strand a consumer.
 *
 * <p><b>Invariant:</b> ordinal order IS the boot order — strictly monotonic, never
 * re-entered, never reordered. Drivers anchor each phase to a native loader event
 * and advance via the SPI; consumers only observe. The fifth transition
 * ({@link #SERVER_UP}) is the engine-ready boot marker the TCK boot smoke asserts.
 */
public enum EnginePhase {
    /** Engine core initialized; exactly one driver bound. */
    VINE_BOOT,
    /** Content registration is open; descriptors may be declared. */
    REGISTRIES_OPEN,
    /** Registries frozen; content ids are stable from here on. */
    REGISTRIES_FROZEN,
    /** World data loading; persistence layer live. */
    WORLD_LOAD,
    /** Server ready; engine fully up. Boot-marker phase (fifth transition line). */
    SERVER_UP
}
