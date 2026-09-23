package dev.vineengine.vine.internal.driver1211.common.events;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Engine command literals queued for native registration (M0 minimal-command
 * hook). Both loaders drain this queue from their native command-registration
 * event ({@code RegisterCommandsEvent} / {@code CommandRegistrationCallback}) and
 * route executions onto the {@link VineHook#COMMAND_EXECUTE} hook.
 *
 * <p>Literals must be queued before the loader's command pass runs (during
 * {@code REGISTRIES_OPEN}); queueing later is an explicit failure, never a silent
 * no-op. The full command API is sub-06's — this is the driver's M0 dispatch
 * mechanism only.
 */
public final class CommandQueue {

    private final Set<String> literals = new LinkedHashSet<>();
    private boolean drained;

    /** Queues a literal for the next native command-registration pass. */
    public synchronized void register(String literal) {
        if (drained) {
            throw new IllegalStateException("engine command '" + literal
                + "' queued after the native command-registration pass — queue during REGISTRIES_OPEN");
        }
        literals.add(Objects.requireNonNull(literal, "literal"));
    }

    /** Snapshot of queued literals; marks the queue drained (native pass ran). */
    public synchronized Set<String> drain() {
        drained = true;
        return Set.copyOf(literals);
    }
}
