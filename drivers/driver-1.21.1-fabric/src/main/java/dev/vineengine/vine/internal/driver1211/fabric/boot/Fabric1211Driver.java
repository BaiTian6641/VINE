package dev.vineengine.vine.internal.driver1211.fabric.boot;


import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.SharedConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.combat.VineCombat;
import dev.vineengine.vine.internal.driver1211.common.CellProbes;
import dev.vineengine.vine.internal.driver1211.common.CellWindow;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookEvents;
import dev.vineengine.vine.quest.VineQuests;
import dev.vineengine.vine.internal.driver1211.common.DriverRuntime;
import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;
import dev.vineengine.vine.internal.driver1211.fabric.command.FabricCommandFactory;
import dev.vineengine.vine.internal.driver1211.fabric.combat.FabricCombatHooks;
import dev.vineengine.vine.internal.data.VoxelStorageBinding;
import dev.vineengine.vine.internal.entity.EntityBinding;
import dev.vineengine.vine.internal.driver1211.common.data.VoxelProbe;
import dev.vineengine.vine.internal.driver1211.common.persistence.FileWorldStore;
import dev.vineengine.vine.internal.driver1211.fabric.data.FabricVoxelStorage;
import dev.vineengine.vine.internal.driver1211.fabric.data.FabricWorldView;
import dev.vineengine.vine.internal.driver1211.fabric.entity.FabricEntityDriver;
import dev.vineengine.vine.internal.driver1211.fabric.events.FabricHookInstallers;
import dev.vineengine.vine.internal.driver1211.fabric.net.FabricNetDriver;
import dev.vineengine.vine.internal.driver1211.fabric.net.FabricPartTransport;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricBehaviorWiring;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricDesignMaterializer;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricStructuralMaterializer;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.spi.VineDriver;
import dev.vineengine.vine.internal.world.WorldViewBinding;

/**
 * The 1.21.1 Fabric {@link VineDriver} (ServiceLoader-bound, one per cell).
 *
 * <p>Phase anchors (loader-difference absorbed — Fabric's flat lifecycle, not
 * NF's mod bus):
 * <ul>
 *   <li>{@code REGISTRIES_OPEN} — {@code onInitialize}: mods register content
 *       here, so registration is open by definition.</li>
 *   <li>{@code REGISTRIES_FROZEN} — Fabric has no registry-freeze callback;
 *       vanilla freezes the builtin registries during server bootstrap, so the
 *       first observable frozen moment is {@code SERVER_STARTING}. Phases are
 *       replaying states, so this later anchor cannot strand consumers.</li>
 *   <li>{@code WORLD_LOAD} — {@code SERVER_STARTING}: world data begins loading
 *       immediately after.</li>
 *   <li>{@code SERVER_UP} — {@code SERVER_STARTED}: vanilla {@code "Done"} has
 *       been logged; the engine-ready marker follows it.</li>
 * </ul>
 */
public final class Fabric1211Driver implements VineDriver {

    /** Driver id used in failure messages and logs (never a version-string parse). */
    public static final String DRIVER_ID = "1.21.1-fabric";

    private static final Logger LOG = LoggerFactory.getLogger(Fabric1211Driver.class);

    /** Captured at bootstrap; read by {@link #materializeStructuralRegistries()} after boot. */
    private static volatile DriverContext driverContext;

    /** Public no-arg constructor required by {@code ServiceLoader}. */
    public Fabric1211Driver() {
    }

    @Override
    public CellInfo cell() {
        // Data-version probe via Yarn GameVersion (NeoForge reads the same number
        // through Mojmap names) — §5.12: never version strings.
        int dataVersion = SharedConstants.getGameVersion().getSaveVersion().getId();
        CellWindow.check(DRIVER_ID, dataVersion);
        LOG.info("[VINE] driver {} bound to cell: dataVersion={} probes={}",
            DRIVER_ID, dataVersion, CellProbes.probeMatrix());
        return new CellInfo(dataVersion, LoaderFamily.FABRIC, CellProbes.supportedFeatures());
    }

    private static volatile net.minecraft.server.MinecraftServer currentServer;

    /** The running server, or {@code null} before start / after stop (probe plumbing). */
    public static net.minecraft.server.MinecraftServer currentServer() {
        return currentServer;
    }

    @Override
    public void bootstrap(DriverContext ctx) {
        ctx.advancePhase(EnginePhase.REGISTRIES_OPEN);
        driverContext = ctx;
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            currentServer = server;
            // Player-name lookup (sub-06 Stage C): the engine knows session
            // participants by UUID; naming them for suggestions is a cell concern.
            dev.vineengine.vine.internal.PlayerNames.install(uuid -> {
                net.minecraft.server.network.ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                return player == null ? null : player.getGameProfile().getName();
            });
            // Player lookup (sub-14 Stage C): session snapshots target a UUID.
            dev.vineengine.vine.internal.Players.install(uuid -> {
                net.minecraft.server.network.ServerPlayerEntity player = server.getPlayerManager().getPlayer(uuid);
                return player == null ? null : dev.vineengine.vine.internal.driver1211.fabric.command
                    .FabricCommandFactory.playerOf(player);
            });
            // Schemas must be registered by consumers/drivers *after* engine boot
            // (bootstrap runs inside it, where the facade is deliberately blocked)
            // and before the store freezes — this anchor is exactly that window.
            VoxelProbe.registerSchemas();
            ctx.advancePhase(EnginePhase.REGISTRIES_FROZEN);
            // Sub-07 Stage C: the cell-side half of the stage's Minimal Footprint
            // assertion — what this cell actually wired, printed next to the engine's
            // own "[VINE] behaviors: ticking block entities=N" line so the two counts
            // are read together.
            LOG.info(FabricBehaviorWiring.installReport());
            ctx.advancePhase(EnginePhase.WORLD_LOAD);
        });

        FabricHookInstallers.bind(ctx, ctx.bus());

        // Networking (sub-05 Stage A): Fabric binds payload types/receivers
        // imperatively, so each engine push binds natively at once.
        FabricNetDriver net = new FabricNetDriver();
        net.bindTransport();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            net.server(server);
            // In-process TCK harness (sub-21 Stage B): the console path a scenario's
            // commands run through. The sink stays empty on purpose: this cell's
            // feedback travels the server's logging stack, and wrapping the source
            // (withOutput) did not divert it — the harness reports such assertions
            // as SKIP rather than guessing, while engine log lines *are* captured
            // and assertable in-process.
            dev.vineengine.vine.internal.ConsoleDispatch.install((command, sink) ->
                server.getCommandManager().executeWithPrefix(server.getCommandSource(), command));
            ctx.advancePhase(EnginePhase.SERVER_UP);
            VoxelProbe.run(FabricVoxelStorage::probeStack, FabricVoxelStorage.probeAccess(), "1.21.1-fabric", FabricVoxelStorage.probeHolders());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            currentServer = null;
            net.server(null);
            dev.vineengine.vine.internal.PlayerNames.install(null);
            dev.vineengine.vine.internal.Players.install(null);
        });

        // Session persistence (sub-14 Stage B): mount on the first world load, flush at save
        // and at stop. The save point is Fabric's server-level callback
        // (ServerLifecycleEvents.BEFORE_SAVE); the per-WORLD save callback ServerWorldEvents
        // lacks is a different seam — the `worldSave` hook slot's Mixin (sub-18 Stage E) —
        // and the mounts below ride the world-load event.
        java.util.concurrent.atomic.AtomicBoolean mounted = new java.util.concurrent.atomic.AtomicBoolean();
        // Voxel storage (sub-03 Stage D): the item-stack attach point plus the
        // acceptance probe. Component registration happens here — mod init is
        // this cell's registration moment, before any item stack exists.
        if (!CellProbes.supportedFeatures().contains(CellProbes.HAS_DATA_COMPONENTS)) {
            // §5.12: the engine's attach points ride data components — a cell
            // without them cannot carry engine data, and that is a boot failure,
            // never a silent degradation.
            throw new IllegalStateException(
                "cell lacks data components (hasDataComponents probe false) — VINE cannot store engine data here");
        }
        FabricVoxelStorage.registerComponent();
        FabricVoxelStorage voxelStorage = new FabricVoxelStorage();
        voxelStorage.engine(VoxelStorageBinding.bind(voxelStorage));
        // World view (sub-07 Stage B): the engine's block-state read/write path onto
        // this cell's live worlds — bound once here, next to the storage seam it
        // mirrors, and reading the server the lifecycle listeners above track.
        WorldViewBinding.bind(new FabricWorldView());
        // Entity primitive (sub-08 Stage A): the cell's spawn path, bound next to
        // the world-view seam it resolves its dimensions through, and before any
        // world can load — the engine never sees an unbound entity seam.
        EntityBinding.bind(new FabricEntityDriver());
        // Actor lifecycle (sub-08 Stage C): removal detaches from inside the
        // entity; chunk unload has no overridable hook, so this cell's one entity
        // listener covers it before any world can load.
        FabricEntityDriver.installUnloadDetach();
        // Part-state sync (sub-08 Stage D): the engine encodes a delta and counts its
        // bytes; resolving which players track the actor is this cell's half, and the
        // payload type must be bound at mod init (Fabric's registration moment) —
        // before any world can host a multipart actor.
        FabricPartTransport.install();
        // Capability interop (sub-04 Stage C/D): native queries answer from the
        // same item payload the storage driver writes.
        dev.vineengine.vine.internal.capability.CapabilityDriverBinding.bind(
            new dev.vineengine.vine.internal.driver1211.common.data.CommonCapabilityDriver(
                FabricVoxelStorage::payloadOf));
        // Combat (sub-10 Stage D): Fabric's pre-attack and native-damage events normalized
        // onto the engine's one pipeline (see FabricCombatHooks for the ownership split).
        FabricCombatHooks.install();
        // Engine state that decays with the world clock, once per server tick: combat
        // i-frames and hitstop, and the quest service's queued event batch. The cell owns the
        // clock — the engine has no tick loop of its own.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            VineCombat.state().tick();
            VineQuests.tick();
        });
        ServerWorldEvents.LOAD.register((server, world) -> {
            if (mounted.compareAndSet(false, true)) {
                // Working directory = the run/module dir in dev and the server
                // root in production; the default level lives at <cwd>/world
                // (documented assumption — a renamed level-name would need the
                // server-properties read).
                ctx.mountWorldStore(new FileWorldStore(
                    java.nio.file.Path.of("world", "vine").toAbsolutePath()));
            }
        });
        // Persistence (sub-03 Stage D): `open` hands back a live tree, so everything the
        // engine wrote into one — part wounds, an item's durability, a player's capability
        // state — reaches its holder only when the cell flushes. Fabric's save callback is
        // the per-save point, and the stop point is what makes a crash-free shutdown durable.
        ServerLifecycleEvents.BEFORE_SAVE.register((server, flush, force) ->
            flushVoxelTrees(voxelStorage, "save"));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            flushVoxelTrees(voxelStorage, "stop");
            ctx.flushWorldStore();
        });

        // Commands (sub-06 Stage A): Fabric's command callback fires at dispatcher
        // construction — before SERVER_STARTING on this cell, but the descriptor
        // snapshot is complete (consumer initializers ran during REGISTRIES_OPEN),
        // and the engine's native-pass snapshot is stable per build.
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            EngineCommands.attach(dispatcher, FabricCommandFactory.instance(), LOG));
    }

    /**
     * Fabric's structural materialization moment (sub-02 Stage B): plain mod init,
     * via {@code FabricRegistryBuilder} + {@code Registry.register}. Loader
     * difference absorbed vs. NF (which phases registration through
     * {@code RegisterEvent}): called by {@code VineFabricMod} right after
     * {@code DriverBoot.boot} returns, so consumer initializers have already run
     * and the store snapshot is complete for this session.
     */
    public static void materializeStructuralRegistries() {
        DriverContext ctx = driverContext;
        if (ctx == null) {
            throw new IllegalStateException(
                "Fabric1211Driver.bootstrap has not run — entrypoint wiring broken");
        }
        new FabricStructuralMaterializer().materializeStructural(ctx.structuralRegistries());
        // Design descriptors (sub-02 Stage C): dynamic datapack registries
        // register at the same mod-init moment, before any world loads.
        new FabricDesignMaterializer(ctx).registerDesign(ctx.designRegistries());
    }

    /**
     * The structural descriptors this cell's driver was handed — every registered block,
     * item, entity, action and attack, in the store's sorted order.
     *
     * <p>Read by the content-cooking path ({@code FabricContentCooker}): a partner weapon's
     * preset is cooked from the descriptors that were actually registered, including the
     * ones Java initializers declared, so the pass asks the snapshot rather than reading the
     * JSON a second time and hoping the two agree.
     *
     * @throws IllegalStateException before {@code bootstrap} has run — the handle is the one
     *     the engine gave this driver, so it exists exactly when the driver does
     */
    public static StructuralRegistryView structuralView() {
        DriverContext ctx = driverContext;
        if (ctx == null) {
            throw new IllegalStateException(
                "Fabric1211Driver.bootstrap has not run — boot the driver (DriverBoot.boot) first");
        }
        return ctx.structuralRegistries();
    }

    /**
     * Flushes every open voxel tree into its holder and reports the count (sub-03 Stage D).
     * Until this runs, a tree the engine wrote — part wounds, an item's durability, a
     * player's state — lives only in memory: an open tree is handed back to every caller and
     * reaches the holder solely on a flush.
     */
    private static void flushVoxelTrees(FabricVoxelStorage storage, String moment) {
        int written = storage.flushAll();
        if (written > 0) {
            LOG.info("[VINE] voxeldata: flushed {} open tree(s) at {} (Fabric)", written, moment);
        }
    }
}
