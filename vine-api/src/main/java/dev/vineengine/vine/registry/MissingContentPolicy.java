package dev.vineengine.vine.registry;

/**
 * What happens at world load when the persistent {@code VineId} map references
 * content a consumer no longer registers (sub-02 Stage D, plan §5.2). Set per
 * consumer namespace through
 * {@link VineRegistries#setMissingContentPolicy}; {@link #KEEP} is the default.
 */
public enum MissingContentPolicy {

    /**
     * Retain the mapping (and therefore every data reference to it): the content
     * is absent, but re-adding it later restores the original runtime id.
     */
    KEEP,

    /** Release the mapping; the next new id may reuse the slot. */
    DROP,

    /** Refuse the world load with an explicit failure naming the missing ids. */
    FAIL
}
