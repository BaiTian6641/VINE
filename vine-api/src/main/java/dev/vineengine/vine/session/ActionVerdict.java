package dev.vineengine.vine.session;

/**
 * The verdict of {@link SessionRules#validate} (sub-14 §2) — server
 * authority expressed as a value, never an exception.
 */
public sealed interface ActionVerdict permits ActionVerdict.Allow, ActionVerdict.Deny {

    record Allow() implements ActionVerdict {
    }

    record Deny(String reason) implements ActionVerdict {
    }

    /** The shared allow singleton. */
    ActionVerdict ALLOW = new Allow();

    /** Convenience: a denial with {@code reason}. */
    static ActionVerdict deny(String reason) {
        return new Deny(reason);
    }
}
