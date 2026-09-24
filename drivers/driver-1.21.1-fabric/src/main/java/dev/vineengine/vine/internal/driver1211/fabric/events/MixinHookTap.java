package dev.vineengine.vine.internal.driver1211.fabric.events;

import java.util.function.Consumer;

import dev.vineengine.vine.VineEvent;
import dev.vineengine.vine.hook.HookEvents;

/**
 * Static tap behind the two Mixin-backed hook slots (sub-18 Stage E, Mixin
 * Quarantine §5.9): {@code BlockItemPlaceMixin} and {@code ServerWorldSaveMixin}
 * sit in vanilla's own placement/save path on this loader and hand their
 * normalized payload to whatever sink is bound here; the slot install binds the
 * engine-bus sink, the slot uninstall clears it (@{code null}).
 *
 * <p>Unlike the engine-owned {@code RegistryHookTap}, the native source here is
 * a Mixin and therefore always applied — a loader-API listener could not be
 * unregistered, but an injection site cannot be switched off either. Minimal
 * Footprint (§5.1) is preserved at the observable level: with zero consumers the
 * injected body is one volatile read and a null check, so no event object is
 * allocated and the vanilla action is untouched. That is the deviation the
 * stage's prose records.
 *
 * <p>Both sinks are volatile because the sides differ: install/uninstall run on
 * the subscribing thread, while the injected sites run wherever vanilla calls
 * them — the server thread for a world save or a player placement.
 */
public final class MixinHookTap {

    private static volatile Consumer<VineEvent> blockPlaceSink;
    private static volatile Consumer<VineEvent> worldSaveSink;

    private MixinHookTap() {
    }

    /** Binds the sink for the {@code blockPlace} slot; {@code null} unbinds (hook dormancy). */
    public static void blockPlace(Consumer<VineEvent> sink) {
        blockPlaceSink = sink;
    }

    /** Binds the sink for the {@code worldSave} slot; {@code null} unbinds (hook dormancy). */
    public static void worldSave(Consumer<VineEvent> sink) {
        worldSaveSink = sink;
    }

    /**
     * Posts one {@link HookEvents.BlockPlace} payload; {@code true} means a
     * consumer vetoed it, so the caller must not apply the block (NeoForge's
     * {@code EntityPlaceEvent} cancellation semantics). A no-op — and no
     * allocation — without a sink.
     */
    public static boolean postBlockPlace(String worldId, String blockId, int x, int y, int z,
            String playerUuid) {
        Consumer<VineEvent> sink = blockPlaceSink;
        if (sink == null) {
            return false;
        }
        HookEvents.BlockPlace event = new HookEvents.BlockPlace(worldId, blockId, x, y, z, playerUuid);
        sink.accept(event);
        return event.isCancelled();
    }

    /** Posts one {@link HookEvents.WorldSave} payload; a no-op without a sink. */
    public static void postWorldSave(String worldId) {
        Consumer<VineEvent> sink = worldSaveSink;
        if (sink != null) {
            sink.accept(new HookEvents.WorldSave(worldId));
        }
    }
}
