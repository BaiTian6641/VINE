package dev.vineengine.vine.internal.driver1211.common.events;

/**
 * The M0 hook set translated by the 1.21.1 drivers (sub-18 §2 table, M0 subset):
 * the native loader sources that back engine-visible events. Each hook installs
 * its native listener lazily on first subscription and uninstalls on last close
 * (Minimal Footprint, §5.1) via {@link HookBus}.
 *
 * <p>Seams pending sibling SPIs (documented, never silently absent): registration
 * materialization waits on sub-02's driver registry SPI (sub-18 Stage D), packet
 * receive on sub-05's payload SPI, and the Fabric rows with no native callback
 * (block place, world save) wait on the quarantined per-driver Mixin config —
 * subscribing to one of these hooks throws instead of misfiring.
 */
public enum VineHook {
    /** Engine content registration observation. Seam: sub-02 driver registry SPI (Stage D). */
    REGISTRY_REGISTER,
    /** A block was placed in a world. NF: {@code EntityPlaceEvent}; Fabric: †Mixin (deferred). */
    BLOCK_PLACE,
    /** A block was broken. NF: {@code BlockEvent.BreakEvent}; Fabric: {@code PlayerBlockBreakEvents.AFTER}. */
    BLOCK_BREAK,
    /** A world finished loading. NF: {@code LevelEvent.Load}; Fabric: {@code ServerWorldEvents.LOAD}. */
    WORLD_LOAD,
    /** A world is being saved. NF: {@code LevelEvent.Save}; Fabric: †Mixin (deferred). */
    WORLD_SAVE,
    /** An engine payload arrived. Seam: sub-05 payload SPI (Stage D). */
    PACKET_RECEIVE,
    /** A queued engine command literal executed. NF: {@code RegisterCommandsEvent}; Fabric: {@code CommandRegistrationCallback}. */
    COMMAND_EXECUTE
}
