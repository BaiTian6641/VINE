package dev.vineengine.vine.internal.driver1211.neoforge.boot;

import net.minecraft.SharedConstants;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.internal.driver1211.common.CellProbes;
import dev.vineengine.vine.internal.driver1211.common.CellWindow;
import dev.vineengine.vine.internal.driver1211.common.DriverRuntime;
import dev.vineengine.vine.internal.driver1211.common.events.CommandQueue;
import dev.vineengine.vine.internal.driver1211.common.events.HookBus;
import dev.vineengine.vine.internal.driver1211.neoforge.events.NeoForgeHookInstallers;
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
        modBus.addListener(FMLCommonSetupEvent.class,
            event -> ctx.advancePhase(EnginePhase.REGISTRIES_FROZEN));
        // Structural descriptor materialization (sub-02 Stage B): wires itself to
        // NewRegistryEvent/RegisterEvent on the same mod bus.
        new NeoForgeStructuralMaterializer(modBus, ctx);
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class,
            event -> ctx.advancePhase(EnginePhase.WORLD_LOAD));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class,
            event -> ctx.advancePhase(EnginePhase.SERVER_UP));

        CommandQueue commands = new CommandQueue();
        DriverRuntime.install(new HookBus(NeoForgeHookInstallers.create(commands), commands));
        LOG.debug("[VINE] driver {} bootstrap complete: hook collectors armed (0 native listeners)", DRIVER_ID);
    }
}
