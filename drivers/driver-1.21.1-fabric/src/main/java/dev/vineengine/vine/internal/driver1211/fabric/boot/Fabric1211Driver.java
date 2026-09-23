package dev.vineengine.vine.internal.driver1211.fabric.boot;


import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.SharedConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.internal.driver1211.common.CellProbes;
import dev.vineengine.vine.internal.driver1211.common.CellWindow;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookEvents;
import dev.vineengine.vine.internal.driver1211.common.DriverRuntime;
import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;
import dev.vineengine.vine.internal.driver1211.fabric.command.FabricCommandFactory;
import dev.vineengine.vine.internal.data.VoxelStorageBinding;
import dev.vineengine.vine.internal.driver1211.common.data.VoxelProbe;
import dev.vineengine.vine.internal.driver1211.common.persistence.FileWorldStore;
import dev.vineengine.vine.internal.driver1211.fabric.data.FabricVoxelStorage;
import dev.vineengine.vine.internal.driver1211.fabric.events.FabricHookInstallers;
import dev.vineengine.vine.internal.driver1211.fabric.net.FabricNetDriver;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricDesignMaterializer;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricStructuralMaterializer;
import dev.vineengine.vine.internal.spi.VineDriver;

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

    @Override
    public void bootstrap(DriverContext ctx) {
        ctx.advancePhase(EnginePhase.REGISTRIES_OPEN);
        driverContext = ctx;
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // Schemas must be registered by consumers/drivers *after* engine boot
            // (bootstrap runs inside it, where the facade is deliberately blocked)
            // and before the store freezes — this anchor is exactly that window.
            VoxelProbe.registerSchemas();
            ctx.advancePhase(EnginePhase.REGISTRIES_FROZEN);
            ctx.advancePhase(EnginePhase.WORLD_LOAD);
        });

        FabricHookInstallers.bind(ctx, ctx.bus());

        // Networking (sub-05 Stage A): Fabric binds payload types/receivers
        // imperatively, so each engine push binds natively at once.
        FabricNetDriver net = new FabricNetDriver();
        net.bindTransport();
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            net.server(server);
            ctx.advancePhase(EnginePhase.SERVER_UP);
            VoxelProbe.run(FabricVoxelStorage::probeStack, FabricVoxelStorage.probeAccess(), "1.21.1-fabric");
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> net.server(null));

        // Session persistence (sub-14 Stage B): mount on the first world load,
        // flush at stop. Fabric has no per-save callback (documented seam) — the
        // stop flush is the durable point on this loader.
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
        // Capability interop (sub-04 Stage C/D): native queries answer from the
        // same item payload the storage driver writes.
        dev.vineengine.vine.internal.capability.CapabilityDriverBinding.bind(
            new dev.vineengine.vine.internal.driver1211.common.data.ItemStackCapabilityDriver(
                FabricVoxelStorage::rawPayload));
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
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ctx.flushWorldStore());

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
}
