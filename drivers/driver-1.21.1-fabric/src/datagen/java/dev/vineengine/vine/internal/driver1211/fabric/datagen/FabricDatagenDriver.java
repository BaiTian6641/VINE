package dev.vineengine.vine.internal.driver1211.fabric.datagen;

import java.util.Set;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * The {@link VineDriver} the content-cooking JVM binds (sub-02 Stage E + sub-10's partner
 * presets): the cooking run happens in a plain JVM with no game, so the engine binds this
 * driver instead of the loader one — the Fabric bootstrap needs a live loader that only a
 * game has, and the cooking pass needs exactly two things from a driver: a structural
 * registry view, and a phase machine that stays open while consumers register.
 *
 * <p><b>What it deliberately does not do.</b> It installs no listeners, opens no storage,
 * and advances no phase past {@code REGISTRIES_OPEN} — a cooking run must not look like a
 * game boot, or the content it cooks would depend on loader lifecycle the game may order
 * differently. It exists so the cooking entry can read the <em>same</em> descriptor store
 * the game's materialization reads: one answer to "what did the consumer register", never a
 * second parser of the same sources.
 *
 * <p><b>Where it lives.</b> This class is compiled into the cell's {@code datagen} source
 * set, not its mod jar, and its service entry is only on the cooking task's classpath. The
 * game therefore still binds exactly one driver ({@link
 * dev.vineengine.vine.internal.driver1211.fabric.boot.Fabric1211Driver}); two driver
 * services on one classpath is a boot failure, and that is the failure this split avoids.
 *
 * <p><b>The cell facts are the inert minimum.</b> No game runs, so there is no data version
 * to probe and no feature to report: the cooking pass reads descriptors and cooks files, and
 * neither depends on a probe. Nothing here touches a Fabric loader class or Minecraft.
 */
public final class FabricDatagenDriver implements VineDriver {

    /** The engine handle of the current cooking run; {@code null} before {@link #bootstrap}. */
    private static volatile DriverContext context;

    /** Public no-arg constructor required by {@code ServiceLoader}. */
    public FabricDatagenDriver() {
    }

    /** The engine handle of the cooking run, or {@code null} when boot has not happened. */
    static DriverContext context() {
        return context;
    }

    @Override
    public CellInfo cell() {
        return new CellInfo(0, LoaderFamily.FABRIC, Set.of());
    }

    @Override
    public void bootstrap(DriverContext ctx) {
        context = ctx;
        // Registration must be open while the consumer initializers run; the pass ends at
        // the freeze only in a game, and the cooking entry reads the view while open.
        ctx.advancePhase(EnginePhase.REGISTRIES_OPEN);
    }
}
