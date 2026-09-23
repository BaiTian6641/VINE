package dev.vineengine.vine.internal.driver1211.neoforge.boot;


import net.minecraft.SharedConstants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.internal.driver1211.common.CellProbes;
import dev.vineengine.vine.internal.driver1211.common.CellWindow;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.level.LevelEvent;
import dev.vineengine.vine.internal.data.VoxelStorageBinding;
import dev.vineengine.vine.internal.driver1211.common.data.VoxelProbe;
import dev.vineengine.vine.internal.driver1211.common.persistence.FileWorldStore;
import dev.vineengine.vine.internal.driver1211.neoforge.data.NeoForgeVoxelStorage;
import dev.vineengine.vine.internal.driver1211.common.DriverRuntime;
import dev.vineengine.vine.internal.driver1211.common.command.EngineCommands;
import dev.vineengine.vine.internal.driver1211.neoforge.command.NeoForgeCommandFactory;
import dev.vineengine.vine.internal.driver1211.neoforge.events.NeoForgeHookInstallers;
import dev.vineengine.vine.internal.driver1211.neoforge.net.NeoForgeNetDriver;
import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeDesignMaterializer;
import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeStructuralMaterializer;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * The 1.21.1 NeoForge {@link VineDriver} (ServiceLoader-bound, one per cell).
 *
 * <p>Phase anchors (loader-difference absorbed — NF's mod lifecycle, not Fabric's):
 * <ul>
 *   <li>{@code REGISTRIES_OPEN} — mod construction: NF's {@code RegisterEvent} has
 *       not fired yet, so content registration is genuinely open.</li>
 *   <li>{@code REGISTRIES_FROZEN} — {@link FMLCommonSetupEvent}: NF fires it after
 *       all registry events complete and vanilla registries are frozen.</li>
 *   <li>{@code WORLD_LOAD} — {@link ServerAboutToStartEvent}: world data begins
 *       loading.</li>
 *   <li>{@code SERVER_UP} — {@link ServerStartedEvent}: vanilla {@code "Done"} has
 *       been logged; the engine-ready marker follows it.</li>
 * </ul>
 */
public final class NeoForge1211Driver implements VineDriver {

    /** Driver id used in failure messages and logs (never a version-string parse). */
    public static final String DRIVER_ID = "1.21.1-neoforge";

    private static final Logger LOG = LoggerFactory.getLogger(NeoForge1211Driver.class);

    /** Handed off by {@code VineMod} before engine boot; consumed by {@link #bootstrap}. */
    private static volatile IEventBus modEventBus;

    /** Public no-arg constructor required by {@code ServiceLoader}. */
    public NeoForge1211Driver() {
    }

    /** Called by the {@code @Mod} entrypoint with the injected mod event bus. */
    public static void handOffModEventBus(IEventBus bus) {
        modEventBus = bus;
    }

    @Override
    public CellInfo cell() {
        // Data-version probe via Mojmap WorldVersion (Fabric reads the same number
        // through Yarn names) — §5.12: never version strings.
        int dataVersion = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        CellWindow.check(DRIVER_ID, dataVersion);
        LOG.info("[VINE] driver {} bound to cell: dataVersion={} probes={}",
            DRIVER_ID, dataVersion, CellProbes.probeMatrix());
        return new CellInfo(dataVersion, LoaderFamily.NEOFORGE, CellProbes.supportedFeatures());
    }

    @Override
    public void bootstrap(DriverContext ctx) {
        ctx.advancePhase(EnginePhase.REGISTRIES_OPEN);
        IEventBus modBus = modEventBus;
        if (modBus == null) {
            throw new IllegalStateException(
                "VineMod did not hand off the mod event bus before engine boot — entrypoint wiring broken");
        }
        modBus.addListener(FMLCommonSetupEvent.class, event -> {
            // Schemas land after engine boot and before the store freezes.
            VoxelProbe.registerSchemas();
            ctx.advancePhase(EnginePhase.REGISTRIES_FROZEN);
        });
        // Structural descriptor materialization (sub-02 Stage B): wires itself to
        // NewRegistryEvent/RegisterEvent on the same mod bus.
        new NeoForgeStructuralMaterializer(modBus, ctx);
        // Design descriptors (sub-02 Stage C): dynamic datapack registries.
        new NeoForgeDesignMaterializer(modBus, ctx);
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class,
            event -> ctx.advancePhase(EnginePhase.WORLD_LOAD));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            ctx.advancePhase(EnginePhase.SERVER_UP);
            VoxelProbe.run(NeoForgeVoxelStorage::probeStack, NeoForgeVoxelStorage.probeAccess(), "1.21.1-neoforge");
        });

        // Voxel storage (sub-03 Stage D): item-stack attach point + acceptance probe.
        if (!CellProbes.supportedFeatures().contains(CellProbes.HAS_DATA_COMPONENTS)) {
            // §5.12: the engine's attach points ride data components — a cell
            // without them cannot carry engine data, and that is a boot failure,
            // never a silent degradation.
            throw new IllegalStateException(
                "cell lacks data components (hasDataComponents probe false) — VINE cannot store engine data here");
        }
        NeoForgeVoxelStorage.registerComponent(modBus);
        NeoForgeVoxelStorage voxelStorage = new NeoForgeVoxelStorage();
        voxelStorage.engine(VoxelStorageBinding.bind(voxelStorage));
        // Capability interop (sub-04 Stage C/D): native queries answer from the
        // same item payload the storage driver writes.
        dev.vineengine.vine.internal.capability.CapabilityDriverBinding.bind(
            new dev.vineengine.vine.internal.driver1211.common.data.ItemStackCapabilityDriver(
                NeoForgeVoxelStorage::rawPayload));
        // Session persistence (sub-14 Stage B): mount on the first world load,
        // flush on every level save (NF has a real save event).
        java.util.concurrent.atomic.AtomicBoolean mounted = new java.util.concurrent.atomic.AtomicBoolean();
        NeoForge.EVENT_BUS.addListener(LevelEvent.Load.class, event -> {
            if (event.getLevel() instanceof ServerLevel level && mounted.compareAndSet(false, true)) {
                ctx.mountWorldStore(new FileWorldStore(
                    level.getServer().getWorldPath(LevelResource.ROOT).resolve("vine")));
            }
        });
        NeoForge.EVENT_BUS.addListener(LevelEvent.Save.class, event -> {
            if (event.getLevel() instanceof ServerLevel) {
                ctx.flushWorldStore();
            }
        });

        NeoForgeHookInstallers.bind(ctx, ctx.bus());

        // Networking (sub-05 Stage A): engine pushes channel registrations to the
        // driver; native payload types bind when NF fires its payload event.
        NeoForgeNetDriver net = new NeoForgeNetDriver();
        net.bindTransport();
        modBus.addListener(RegisterPayloadHandlersEvent.class, net::bindNative);

        // Commands (sub-06 Stage A): every native dispatcher build attaches the
        // engine's descriptor snapshot (fires post-REGISTRIES_FROZEN on NF).
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent.class,
            event -> EngineCommands.attach(event.getDispatcher(), NeoForgeCommandFactory.instance(), LOG));
    }
}
