package dev.vineengine.vine.command;

/**
 * What an argument's {@link SuggestionSource} is given (sub-06 Stage B): the
 * tokens typed so far for this argument (the partial input), and the source the
 * command is being completed for. Deliberately small — consumers never see a
 * Brigadier or loader type.
 */
public record SuggestionContext(String partial, CommandSourceRef source) {

    public SuggestionContext {
        partial = partial == null ? "" : partial;
    }

    /** Whether {@code candidate} still matches what the user has typed (case-insensitive prefix). */
    public boolean matches(String candidate) {
        return partial.isEmpty() || candidate.toLowerCase(java.util.Locale.ROOT)
            .startsWith(partial.toLowerCase(java.util.Locale.ROOT));
    }
}
