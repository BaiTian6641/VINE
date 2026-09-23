package dev.vineengine.vine.internal.spi;

import java.util.Objects;
import java.util.Set;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookSlot;
import dev.vineengine.vine.registry.VineId;

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
     * <p>Stage A surface is phase advance only; sub-01 Stage B adds {@code bus()},
     * Stage C {@code installHook(...)}, Stage E {@code reportUnsafe(...)} per §2.
     * sub-02 Stage B adds {@link #structuralRegistries()} for descriptor
     * materialization.
     */
    interface DriverContext {

        /**
         * Advances the phase machine toward {@code next}. Intermediate phases are
         * entered (and logged) in order, so every consumer sees every phase exactly
         * once; re-entering the current phase is a no-op; moving backwards throws.
         */
        void advancePhase(EnginePhase next);

        /**
         * Read-only structural slice of the descriptor store (sub-02 Stage B).
         * Snapshot per call; valid from {@link EnginePhase#REGISTRIES_OPEN} on.
         * The driver reads it at its loader's structural-registration moment
         * (NF {@code RegisterEvent}; Fabric mod init) and hands it to its
         * {@link RegistryDriver} implementation.
         */
        StructuralRegistryView structuralRegistries();

        /**
         * The engine event bus (sub-01 Stage B). Driver translation posts
         * normalized hook events here; nothing else in the driver needs a
         * separate collector.
         */
        EventBus bus();

        /**
         * Binds a {@link HookSlot} to its native source (sub-01 Stage C,
         * Minimal Footprint §5.1): {@code install} runs when the first handler
         * for {@code slot.type()} subscribes, {@code uninstall} when the last
         * one closes. Install/uninstall must be idempotent-safe and must never
         * throw; both run on the subscribing thread. The driver's install
         * closure posts the native payload to {@link #bus()} as the slot's
         * event type.
         */
        void installHook(HookSlot slot, Runnable install, Runnable uninstall);

        /**
         * Mounts the per-world engine store (sub-14 Stage B / sub-02 Stage D):
         * the engine restores sessions and the persistent id map from it at
         * once and flushes through it on {@link #flushWorldStore()}.
         */
        void mountWorldStore(WorldStoreSpi spi);

        /**
         * Flushes engine-owned per-world data (sessions + id map) through the
         * mounted store; the driver calls this from its world-save/stop hook.
         * A no-op without a store.
         */
        void flushWorldStore();

        /**
         * Reports the entries a loader's dynamic registry produced for a DESIGN
         * type (sub-02 Stage C) — called after each world load / re-read, and it
         * replaces the previous set, so nothing is cached across worlds. The
         * engine makes them visible through {@code VineRegistries.get} and fires
         * one {@code RegistryRegister} hook event per entry.
         */
        void reportDesignEntries(VineId registryId, java.util.Map<VineId, Object> entries);

        /**
         * Read-only view of the DESIGN descriptor types for datapack-registry
         * registration (sub-02 Stage C). Snapshot per call; valid from
         * {@code REGISTRIES_OPEN} on.
         */
        DesignRegistryView designRegistries();
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
