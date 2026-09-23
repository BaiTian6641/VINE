package dev.vineengine.vine.internal.spi;

import java.util.Objects;
import java.util.Set;

import dev.vineengine.vine.EnginePhase;

/**
 * The one-per-cell driver contract. NOT public API — implemented only by VINE's
 * own driver jars, loaded by vine-core via {@code ServiceLoader}
 * ({@code META-INF/services/dev.vineengine.vine.internal.spi.VineDriver}).
 *
 * <p>A driver is the only versioned code in the engine (§5.12): it reads Mojang's
 * {@code SharedConstants} <b>data version</b> (never version strings) plus runtime
 * class/method-presence probes to build its {@link CellInfo}, and fails the boot
 * explicitly ({@link UnsupportedCellException}) when the runtime is outside its
 * supported data-version window.
 *
 * <p>Boot model: vine-core enters {@link EnginePhase#VINE_BOOT} itself, then calls
 * {@link #bootstrap}. The driver wires its loader entrypoints and advances the
 * remaining phases via {@link DriverContext#advancePhase} — which loader event
 * anchors which phase is the driver's choice, documented in the driver (NF
 * mod-construction vs. Fabric {@code onInitialize} fire at different moments;
 * phases are replaying states, so the choice cannot strand consumers).
 */
public interface VineDriver {

    /**
     * Runtime cell facts, probed from the running game — data version, loader
     * family, and the feature-id set backing {@code VineEngine.supports(...)}.
     * Called once, before {@link #bootstrap}. A driver detecting an out-of-window
     * runtime throws {@link UnsupportedCellException} here.
     */
    CellInfo cell();

    /**
     * Common init: wire the bus, hook slots, config, and loader entrypoints that
     * advance the phase machine to {@link EnginePhase#SERVER_UP}. Called exactly
     * once, in {@link EnginePhase#VINE_BOOT}. Entrypoint split per loader: NF =
     * {@code @Mod("vine")} from common setup; Fabric = {@code ModInitializer} plus
     * client/dedicated-server initializers (a dedicated server must never load
     * client classes, §2).
     */
    void bootstrap(DriverContext ctx);

    /**
     * vine-core's handle handed to the driver during {@link #bootstrap}. Remains
     * valid for the process lifetime — loader lifecycle events may advance phases
     * long after {@code bootstrap} returns.
     *
     * <p>Stage A surface is phase advance only; Stage B adds {@code bus()},
     * Stage C {@code installHook(...)}, Stage E {@code reportUnsafe(...)} per §2.
     */
    interface DriverContext {

        /**
         * Advances the phase machine toward {@code next}. Intermediate phases are
         * entered (and logged) in order, so every consumer sees every phase exactly
         * once; re-entering the current phase is a no-op; moving backwards throws.
         */
        void advancePhase(EnginePhase next);
    }

    /**
     * Immutable runtime cell facts. {@code features} holds plain String feature ids
     * (sub-02's {@code VineId} lands later); copied defensively.
     */
    record CellInfo(int dataVersion, LoaderFamily loader, Set<String> features) {

        public CellInfo {
            Objects.requireNonNull(loader, "loader");
            features = Set.copyOf(Objects.requireNonNull(features, "features"));
        }
    }

    /**
     * Loader families the engine ships drivers for. The family — not the loader
     * brand string — is what behavioral parity is asserted across (§8).
     */
    enum LoaderFamily {
        NEOFORGE,
        FABRIC
    }
}
