package dev.vineengine.vine.internal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.Subscription;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * vine-core's {@link VineEngine} implementation: boots the phase machine and binds
 * exactly one {@link VineDriver} via {@code ServiceLoader}.
 *
 * <p>Boot order: enter {@link EnginePhase#VINE_BOOT} (first transition line), bind
 * the driver, then {@link VineDriver#bootstrap} — the driver's loader entrypoints
 * advance the remaining four phases. Two or more drivers on the classpath is an
 * explicit boot failure; the engine never guesses between cells.
 *
 * <p><b>M0 scaffold deviation (sub-01 Stage A, before sub-00 B/C drivers land):</b>
 * ZERO drivers leaves the engine inert instead of failing — it sits in
 * {@code VINE_BOOT}, serving API calls without touching the runtime (Minimal
 * Footprint §5.1). §2's "zero or ≥2 ⇒ explicit boot failure" rule is restored when
 * real drivers exist.
 */
final class VineEngineImpl implements VineEngine {

    private static final System.Logger LOG = System.getLogger(PhaseMachine.LOG_NAME);

    private final PhaseMachine machine = new PhaseMachine();

    VineEngineImpl() {
        machine.advanceTo(EnginePhase.VINE_BOOT);
        List<VineDriver> drivers = new ArrayList<>();
        for (VineDriver driver : ServiceLoader.load(VineDriver.class)) {
            drivers.add(driver);
        }
        if (drivers.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                "[VINE] no VineDriver service found — engine inert, phases will not advance (Minimal Footprint)");
            return;
        }
        if (drivers.size() > 1) {
            throw new BootFailureException("expected exactly one VineDriver service but found "
                + drivers.size() + ": " + drivers.stream().map(d -> d.getClass().getName()).toList());
        }
        VineDriver driver = drivers.get(0);
        VineDriver.CellInfo cell = Objects.requireNonNull(driver.cell(),
            () -> "driver " + driver.getClass().getName() + " returned null CellInfo");
        LOG.log(System.Logger.Level.INFO,
            "[VINE] driver bound: " + driver.getClass().getName()
                + " (loader=" + cell.loader() + ", dataVersion=" + cell.dataVersion() + ")");
        driver.bootstrap(new CoreDriverContext(machine));
    }

    @Override
    public EnginePhase phase() {
        EnginePhase current = machine.current();
        return current != null ? current : EnginePhase.VINE_BOOT;
    }

    @Override
    public Subscription onPhase(EnginePhase phase, Consumer<PhaseChange> handler) {
        return machine.onPhase(phase, handler);
    }
}
