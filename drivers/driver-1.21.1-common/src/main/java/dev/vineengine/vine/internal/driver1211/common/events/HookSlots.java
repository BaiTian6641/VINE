package dev.vineengine.vine.internal.driver1211.common.events;

import dev.vineengine.vine.hook.HookEvents;
import dev.vineengine.vine.hook.HookSlot;

/**
 * The M0 hook set as engine {@link HookSlot}s (sub-01 Stage C; sub-18 §2 table).
 * The driver binds each slot to its loader's native source at bootstrap; the
 * engine installs on first subscription and uninstalls on last close (Minimal
 * Footprint §5.1). Slots without a source on a loader are not bound there — a
 * subscription then fails loudly instead of sitting silently inert.
 *
 * <p>{@code registryRegister}, {@code packetReceive} and {@code commandExecute}
 * are content-driven: their native plumbing exists for the engine regardless, so
 * "install" just captures the sink the live path posts to.
 */
public final class HookSlots {

    /** NF {@code EntityPlaceEvent}; Fabric seam (no natively observed place; Mixin deferred). */
    public static final HookSlot BLOCK_PLACE = HookSlot.of(
        "blockPlace", HookEvents.BlockPlace.class, "loader block-place event");

    /** NF {@code BlockEvent.BreakEvent}; Fabric {@code PlayerBlockBreakEvents.After}. */
    public static final HookSlot BLOCK_BREAK = HookSlot.of(
        "blockBreak", HookEvents.BlockBreak.class, "loader block-break event");

    /** NF {@code LevelEvent.Load}; Fabric {@code ServerWorldEvents.Load}. */
    public static final HookSlot WORLD_LOAD = HookSlot.of(
        "worldLoad", HookEvents.WorldLoad.class, "loader world-load event");

    /** NF {@code LevelEvent.Save}; Fabric seam (no natively observed save; Mixin deferred). */
    public static final HookSlot WORLD_SAVE = HookSlot.of(
        "worldSave", HookEvents.WorldSave.class, "loader world-save event");

    /** Engine materialization tap (sub-02 Stage B) — no native loader callback. */
    public static final HookSlot REGISTRY_REGISTER = HookSlot.of(
        "registryRegister", HookEvents.RegistryRegister.class, "engine materialization tap");

    /** Engine inbound payload path (sub-05) — native plumbing exists for the engine regardless. */
    public static final HookSlot PACKET_RECEIVE = HookSlot.of(
        "packetReceive", HookEvents.PacketReceive.class, "engine inbound payload path");

    /** Engine command attach path (sub-06) — native plumbing exists for the engine regardless. */
    public static final HookSlot COMMAND_EXECUTE = HookSlot.of(
        "commandExecute", HookEvents.CommandExecute.class, "engine command attach path");

    private HookSlots() {
    }
}
