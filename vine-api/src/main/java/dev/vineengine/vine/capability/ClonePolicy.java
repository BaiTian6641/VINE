package dev.vineengine.vine.capability;

/**
 * Player clone/respawn semantics for a capability (sub-04 §2) — applied by
 * the engine on the verified clone events (NF {@code PlayerEvent.Clone} /
 * Fabric {@code ServerPlayerEvents.COPY_FROM}, copy-on-death only; never on
 * End/dimension return).
 */
public enum ClonePolicy {

    /** The new player starts fresh; nothing is copied. */
    NONE,

    /** The VoxelData state copies to the new player verbatim. */
    FULL,

    /** The provider re-creates the instance on the new player; it may read
     *  the old state itself before returning. */
    CUSTOM
}
