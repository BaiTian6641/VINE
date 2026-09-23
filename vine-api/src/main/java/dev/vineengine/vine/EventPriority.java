package dev.vineengine.vine;

/**
 * Handler ordering within an event type (sub-01 §2): handlers run in
 * {@code FIRST → LAST} order, and registrations of equal priority run in
 * registration order (stable).
 */
public enum EventPriority {
    FIRST,
    EARLY,
    NORMAL,
    LATE,
    LAST
}
