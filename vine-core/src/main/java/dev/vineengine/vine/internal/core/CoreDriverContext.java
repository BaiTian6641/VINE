package dev.vineengine.vine.internal.core;

import java.util.Objects;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * vine-core's half of the driver handshake. Valid for the process lifetime: loader
 * lifecycle events advance phases long after {@code bootstrap} returns.
 */
final class CoreDriverContext implements VineDriver.DriverContext {

    private final PhaseMachine machine;

    CoreDriverContext(PhaseMachine machine) {
        this.machine = machine;
    }

    @Override
    public void advancePhase(EnginePhase next) {
        machine.advanceTo(Objects.requireNonNull(next, "next"));
    }
}
