package dev.vineengine.vine.internal.command;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import dev.vineengine.vine.command.CommandDescriptor;
import dev.vineengine.vine.command.VineCommandExecutor;
import dev.vineengine.vine.internal.CommandBackend;
import dev.vineengine.vine.internal.EngineAccess;

/**
 * The driver-facing seam of the command engine (sub-06 Stage A). NOT public
 * API — called only by VINE's own drivers from their native
 * command-registration hooks ({@code RegisterCommandsEvent} /
 * {@code CommandRegistrationCallback}) and native executor callbacks.
 *
 * <p>Pull model by construction: drivers depend on vine-core, never the
 * reverse, so the driver pulls the conflict-resolved descriptor snapshot at
 * each native dispatcher build ({@link #commandsForNativePass()}) and calls
 * back into the engine only at execution time ({@link #execute}). This is the
 * M0 mechanism standing in for the {@code CommandDriver} SPI contract (sub-06
 * §2), which lands in vine-spi with Stage B; only the transport changes then —
 * descriptors, gates, and executor semantics stay.
 *
 * <p><b>Invariants:</b> the snapshot is idempotent within and across dispatcher
 * builds (Java-registered descriptors are static, sub-06 §2 reload policy);
 * executor exceptions are isolated — logged, error feedback to the source,
 * Brigadier result {@code 0}.
 */
public final class CommandBridge {

    static final String LOG_NAME = "vine.commands";
    private static final System.Logger LOG = System.getLogger(LOG_NAME);

    private CommandBridge() {
    }

    /**
     * Driver-implemented adapter over the native command source
     * (Mojmap {@code CommandSourceStack} / Yarn {@code ServerCommandSource}).
     * Delivers exactly what the engine source facade and feedback channels need —
     * no native type crosses this boundary.
     */
    public interface NativeSource {

        /** The source's display name. */
        String name();

        /** Whether the source is a player. */
        boolean isPlayer();

        /** The player's stable id, or {@code null} when {@link #isPlayer()} is false. */
        UUID playerUniqueId();

        /** Delivers plain-text feedback to this source. */
        void sendFeedback(String message);

        /** Delivers plain-text error feedback to this source. */
        void sendError(String message);
    }

    /**
     * The conflict-resolved descriptor snapshot for one native dispatcher build.
     * Called by the driver on every build; the result is stable post-freeze.
     */
    public static List<CommandDescriptor> commandsForNativePass() {
        return service().snapshotForNativePass();
    }

    /**
     * Invokes one descriptor node's executor with engine context semantics:
     * declared-argument lookup, plain-text feedback routing, and error isolation.
     *
     * @param executor the executor attached to the executed node
     * @param arguments parsed values of the arguments declared on the executed
     *        path, keyed by argument name
     * @param source the native source adapter
     * @return the executor's Brigadier-style result, or {@code 0} after an
     *         isolated executor failure
     */
    /**
     * Evaluates a node's extra requirements (sub-06 Stage B) against the source:
     * a {@code false} predicate denies with an error feedback and the executor
     * never runs. Predicates are consumer code — a throw is a denial, never a
     * dispatcher failure.
     */
    /**
     * The engine source facade for a native source adapter — what requirement
     * predicates and suggestion sources see, so no native type reaches consumer
     * code.
     */
    public static dev.vineengine.vine.command.CommandSourceRef sourceRef(NativeSource source) {
        return new EngineCommandContext(source, Map.of()).source();
    }

    public static boolean requirementsMet(
            List<java.util.function.Predicate<dev.vineengine.vine.command.CommandSourceRef>> requirements,
            NativeSource source) {
        if (requirements.isEmpty()) {
            return true;
        }
        dev.vineengine.vine.command.CommandSourceRef ref = new EngineCommandContext(source, Map.of()).source();
        for (java.util.function.Predicate<dev.vineengine.vine.command.CommandSourceRef> requirement : requirements) {
            boolean allowed;
            try {
                allowed = requirement.test(ref);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "[VINE] command requirement threw for source '"
                    + source.name() + "' — denied: " + e);
                allowed = false;
            }
            if (!allowed) {
                source.sendError("You do not meet this command's requirements.");
                return false;
            }
        }
        return true;
    }

    public static int execute(VineCommandExecutor executor, Map<String, ?> arguments, NativeSource source) {
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(source, "source");
        try {
            return executor.execute(new EngineCommandContext(source, arguments));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] command executor failed for source '"
                + source.name() + "': " + e, e);
            try {
                source.sendError("Command failed: " + e.getMessage());
            } catch (RuntimeException feedbackFailure) {
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] error feedback to '" + source.name() + "' failed: " + feedbackFailure, feedbackFailure);
            }
            return 0;
        }
    }

    private static CommandService service() {
        if (EngineAccess.get() instanceof CommandBackend backend
            && backend.commands() instanceof CommandService service) {
            return service;
        }
        throw new IllegalStateException(
            "vine-core engine does not provide command services — mismatched vine-api/vine-core jars");
    }
}
