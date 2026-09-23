package dev.vineengine.vine.session;

/**
 * Session lifecycle phases (sub-14 §2). Transitions are strictly ordered and
 * service-validated: {@code CREATED → ACTIVE → COMPLETED | ABANDONED};
 * terminal phases never transition.
 */
public enum SessionPhase {
    CREATED,
    ACTIVE,
    COMPLETED,
    ABANDONED
}
