package dev.vineengine.vine.internal.spi;

/**
 * Driver contract for structural descriptor materialization (sub-02 §2). One
 * implementation per cell, wired by the driver's bootstrap; consumers never see it.
 *
 * <p>The SPI contract is only <b>"materialized before vanilla freeze"</b> — the
 * loader's registration moment differs per cell (NF: {@code RegisterEvent};
 * Fabric: {@code Registry.register} at mod init), and the driver absorbs that
 * difference. Structural descriptors land in one vanilla static registry per
 * descriptor type, keyed by the type's {@code registryId}; values are the
 * descriptor data objects (per-type native delegates are the content
 * subsystems' concern, e.g. sub-07 for blocks).
 *
 * <p>On completion the driver logs {@code vine: materialized N structural
 * entries} (skipped when zero — Minimal Footprint, §5.1); each materialized
 * entry is also dispatched to the {@code REGISTRY_REGISTER} hook tap.
 */
public interface RegistryDriver {

    /**
     * Materializes every structural descriptor in {@code structural} into the
     * loader's static registries. Invoked by the driver's own loader wiring at
     * the cell's registration moment, never by vine-core and never by consumers;
     * must complete before the loader freezes its registries.
     */
    void materializeStructural(StructuralRegistryView structural);

    /**
     * Registers every DESIGN descriptor type into the loader's dynamic datapack
     * registries (sub-02 Stage C). Driven by the driver's loader wiring on the
     * dynamic-registry registration moment (NF {@code DataPackRegistryEvent.NewRegistry},
     * Fabric {@code DynamicRegistries.registerSynced}); entries themselves arrive
     * from datapacks per world and are reported through
     * {@link VineDriver.DriverContext#reportDesignEntries}.
     */
    void registerDesign(DesignRegistryView design);
}
