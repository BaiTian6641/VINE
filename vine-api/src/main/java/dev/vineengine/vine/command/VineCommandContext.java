package dev.vineengine.vine.command;

/**
 * Execution context handed to a {@link VineCommandExecutor} (sub-06 §2). The
 * only window a command executor has into the engine: the source facade, the
 * declared argument values, and plain-text feedback channels.
 *
 * <p>Feedback is plain text at M1 (no text-component DSL — sub-06 §1
 * non-goals). Both feedback methods deliver to the executing source only,
 * never broadcast.
 */
public interface VineCommandContext {

    /** Who/what executed the command. */
    CommandSourceRef source();

    /**
     * The parsed value of a declared argument.
     *
     * @throws IllegalArgumentException if {@code name} was not declared on the
     *         executed path, or the value is not of {@code type} — an authoring
     *         bug, surfaced explicitly rather than as a {@code null}
     */
    <T> T argument(String name, Class<T> type);

    /** Sends plain-text feedback to the executing source. */
    void feedback(String message);

    /** Sends plain-text error feedback to the executing source. */
    void error(String message);
}
