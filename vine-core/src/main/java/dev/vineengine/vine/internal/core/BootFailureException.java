package dev.vineengine.vine.internal.core;

/**
 * Explicit engine boot failure (sub-01 §2: zero/ambiguous drivers must never become
 * a silent misfire). Unchecked — boot failures are not recoverable at runtime.
 */
final class BootFailureException extends RuntimeException {

    BootFailureException(String message) {
        super(message);
    }
}
