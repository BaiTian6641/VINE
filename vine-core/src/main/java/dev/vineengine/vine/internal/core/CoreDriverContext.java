package dev.vineengine.vine.internal.core;

import java.util.Objects;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.internal.registry.DescriptorStore;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * vine-core's half of the driver handshake. Valid for the process lifetime: loader
 * lifecycle events advance phases (and read the structural view) long after
 * {@code bootstrap} returns.
 */
final class CoreDriverContext implements VineDriver.DriverContext {

    private final PhaseMachine machine;
    private final DescriptorStore registries;

    CoreDriverContext(PhaseMachine machine, DescriptorStore registries) {
        this.machine = machine;
        this.registries = registries;
    }

    @Override
    public void advancePhase(EnginePhase next) {
        machine.advanceTo(Objects.requireNonNull(next, "next"));
    }

    @Override
    public StructuralRegistryView structuralRegistries() {
        return registries.structuralView();
    }
}
