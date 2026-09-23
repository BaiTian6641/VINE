package dev.vineengine.vine.internal.driver1211.common.events;

/**
 * The M0 hook set translated by the 1.21.1 drivers (sub-18 §2 table, M0 subset):
 * the native loader sources that back engine-visible events. Each hook installs
 * its native listener lazily on first subscription and uninstalls on last close
 * (Minimal Footprint, §5.1) via {@link HookBus}.
 *
 * <p>Seams pending sibling work (documented, never silently absent): the Fabric
 * rows with no native callback (block place, world save) wait on the quarantined
 * per-driver Mixin config — subscribing to one of these hooks throws instead of
 * misfiring. {@link #REGISTRY_REGISTER} is live since sub-02 Stage B (engine
 * materialization tap); {@link #PACKET_RECEIVE} and {@link #COMMAND_EXECUTE} are
 * live since sub-05/sub-06 Stage A — content-driven hooks whose installers
 * capture the sink the live path posts to.
 */
public enum VineHook {
    /** A structural descriptor was materialized into a vanilla static registry. Source: sub-02 Stage B materialization tap. */
    REGISTRY_REGISTER,
    /** A block was placed in a world. NF: {@code EntityPlaceEvent}; Fabric: †Mixin (deferred). */
    BLOCK_PLACE,
    /** A block was broken. NF: {@code BlockEvent.BreakEvent}; Fabric: {@code PlayerBlockBreakEvents.AFTER}. */
    BLOCK_BREAK,
    /** A world finished loading. NF: {@code LevelEvent.Load}; Fabric: {@code ServerWorldEvents.LOAD}. */
    WORLD_LOAD,
    /** A world is being saved. NF: {@code LevelEvent.Save}; Fabric: †Mixin (deferred). */
    WORLD_SAVE,
    /** An engine payload arrived. Live since sub-05 Stage A: the NetDriver inbound path (NF payload handler / Fabric play receiver). */
    PACKET_RECEIVE,
    /** An engine command executed. Live since sub-06 Stage A: the EngineCommands attach path (NF RegisterCommandsEvent / Fabric CommandRegistrationCallback). */
    COMMAND_EXECUTE
}
