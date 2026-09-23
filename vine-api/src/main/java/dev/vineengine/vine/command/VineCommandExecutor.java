package dev.vineengine.vine.command;

/**
 * The behavior attached to a command node (sub-06 §2). Runs server-side on the
 * server's main thread, after the node's permission gate passed.
 *
 * <p>Result follows the Brigadier convention: {@code 1} (or any positive
 * count) for success, {@code 0} for failure. An executor throwing
 * {@link RuntimeException} is isolated by the engine: the failure is logged,
 * the source receives error feedback, and the result is {@code 0} — one broken
 * consumer command can never take down the dispatcher.
 */
@FunctionalInterface
public interface VineCommandExecutor {

    /**
     * Executes the command.
     *
     * @return Brigadier-style result: positive on success, {@code 0} on failure
     */
    int execute(VineCommandContext context);
}
