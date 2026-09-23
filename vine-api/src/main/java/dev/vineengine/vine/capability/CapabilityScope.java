package dev.vineengine.vine.capability;

/**
 * The four attachment scopes a capability provider binds to (sub-04 §2):
 * a provider registered for a scope serves every target of that kind —
 * per-target instances are created lazily on first {@code find}.
 */
public enum CapabilityScope {
    BLOCK,
    ENTITY,
    ITEM,
    PLAYER
}
