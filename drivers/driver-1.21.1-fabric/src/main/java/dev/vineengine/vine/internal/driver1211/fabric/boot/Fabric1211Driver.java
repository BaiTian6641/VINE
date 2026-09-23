package dev.vineengine.vine.internal.driver1211.fabric.boot;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.SharedConstants;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.internal.driver1211.common.CellProbes;
import dev.vineengine.vine.internal.driver1211.common.CellWindow;
import dev.vineengine.vine.internal.driver1211.common.DriverRuntime;
import dev.vineengine.vine.internal.driver1211.common.events.CommandQueue;
import dev.vineengine.vine.internal.driver1211.common.events.HookBus;
import dev.vineengine.vine.internal.driver1211.fabric.events.FabricHookInstallers;
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
            ctx.advancePhase(EnginePhase.REGISTRIES_FROZEN);
            ctx.advancePhase(EnginePhase.WORLD_LOAD);
        });
        ServerLifecycleEvents.SERVER_STARTED.register(server -> ctx.advancePhase(EnginePhase.SERVER_UP));

        CommandQueue commands = new CommandQueue();
        DriverRuntime.install(new HookBus(FabricHookInstallers.create(commands), commands));
        LOG.debug("[VINE] driver {} bootstrap complete: hook collectors armed (0 native listeners)", DRIVER_ID);
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
    }
}
