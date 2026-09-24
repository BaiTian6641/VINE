package dev.vineengine.vine.internal.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.function.Consumer;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.ExtensionPoints;
import dev.vineengine.vine.Feature;
import dev.vineengine.vine.Subscription;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.capability.CapabilityProvider;
import dev.vineengine.vine.capability.CapabilityScope;
import dev.vineengine.vine.capability.CapabilityTarget;
import dev.vineengine.vine.capability.CapabilityType;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelDataFixer;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.internal.CapabilityBackend;
import dev.vineengine.vine.internal.CommandBackend;
import dev.vineengine.vine.internal.ConfigBackend;
import dev.vineengine.vine.internal.NetBackend;
import dev.vineengine.vine.internal.RegistryBackend;
import dev.vineengine.vine.internal.SessionBackend;
import dev.vineengine.vine.internal.VoxelBackend;
import dev.vineengine.vine.internal.capability.CapabilityStore;
import dev.vineengine.vine.internal.command.CommandService;
import dev.vineengine.vine.internal.config.ConfigService;
import dev.vineengine.vine.internal.data.NativeFields;
import dev.vineengine.vine.internal.data.SchemaRegistry;
import dev.vineengine.vine.internal.data.VoxelBlobCodec;
import dev.vineengine.vine.internal.data.VoxelStorageBinding;
import dev.vineengine.vine.internal.registry.IdMapStore;
import dev.vineengine.vine.internal.command.CommandJsonLoader;
import dev.vineengine.vine.internal.registry.StructuralJsonLoader;
import dev.vineengine.vine.internal.net.VineNetImpl;
import dev.vineengine.vine.internal.registry.DescriptorStore;
import dev.vineengine.vine.internal.session.SessionService;
import dev.vineengine.vine.internal.spi.VineDriver;
import dev.vineengine.vine.internal.spi.VoxelStorageDriver;
import dev.vineengine.vine.net.VineNet;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.session.SessionFactory;
import dev.vineengine.vine.session.SessionManager;

/**
 * vine-core's {@link VineEngine} implementation: boots the phase machine and binds
 * exactly one {@link VineDriver} via {@code ServiceLoader}. Also the engine's
 * {@link RegistryBackend} — {@code VineRegistries} resolves this object through
 * the same boot seam, so registry calls inherit the one-provider rule.
 *
 * <p>Boot order: enter {@link EnginePhase#VINE_BOOT} (first transition line), bind
 * the driver, then {@link VineDriver#bootstrap} — the driver's loader entrypoints
 * advance the remaining four phases. Two or more drivers on the classpath is an
 * explicit boot failure; the engine never guesses between cells.
 *
 * <p>The {@link DescriptorStore} freezes when {@link EnginePhase#REGISTRIES_FROZEN}
 * is entered; the freeze listener is registered before the driver can advance
 * phases, so the store is frozen before any consumer's phase listener observes it.
 *
 * <p><b>M0 scaffold deviation (sub-01 Stage A, before sub-00 B/C drivers land):</b>
 * ZERO drivers leaves the engine inert instead of failing — it sits in
 * {@code VINE_BOOT}, serving API calls without touching the runtime (Minimal
 * Footprint §5.1). §2's "zero or ≥2 ⇒ explicit boot failure" rule is restored when
 * real drivers exist.
 */
final class VineEngineImpl implements VineEngine, RegistryBackend, NetBackend, CommandBackend,
        VoxelBackend, CapabilityBackend, SessionBackend, ConfigBackend {

    private static final System.Logger LOG = System.getLogger(PhaseMachine.LOG_NAME);

    private final PhaseMachine machine = new PhaseMachine();
    private final EngineEventBus bus = new EngineEventBus();
    private volatile FeatureMatrix features = FeatureMatrix.EMPTY;
    private final DescriptorStore registries = new DescriptorStore();
    private final VineNetImpl net = new VineNetImpl(bus);
    private final CommandService commands = new CommandService();

    /** The command service (package seam for the JSON command load in {@code ConsumerInitializers}). */
    CommandService commandsService() {
        return commands;
    }

    private final SchemaRegistry schemas = new SchemaRegistry();
    private final CapabilityStore capabilities = new CapabilityStore();
    private final SessionService sessions = new SessionService();
    private final ConfigService config = new ConfigService();
    private final ExtensionPointsImpl extensions = new ExtensionPointsImpl();
    private final IdMapStore idMap = new IdMapStore();

    VineEngineImpl() {
        // Phase changes are engine events too (sub-01 Stage B): every entry is
        // posted to the bus, then the replaying one-shot subscribers fire.
        machine.onTransition(entered -> bus.post(new PhaseChange(entered)));
        // Engine-owned content kinds (sub-07): defined before the driver boots
        // and before any consumer initializer runs, so registration under
        // VineContent tokens is legal from the first REGISTRIES_OPEN moment and
        // the driver's structural view can never miss the types.
        registries.idMap(idMap);
        registries.defineType(VineContent.BLOCK_TYPE);
        registries.defineType(VineContent.ITEM_TYPE);
        machine.onPhase(EnginePhase.REGISTRIES_FROZEN, change -> {
            // Structural JSON authoring (sub-02 Stage F) lands before the freeze:
            // consumer types are defined, and JSON entries become ordinary
            // registrations — read once, never hot-reloadable.
            StructuralJsonLoader.Result jsonResult = StructuralJsonLoader.load(registries,
                Thread.currentThread().getContextClassLoader() != null
                    ? Thread.currentThread().getContextClassLoader()
                    : VineEngineImpl.class.getClassLoader(),
                ConsumerInitializers.codeSources());
            // Logged for the TCK's cross-loader parity check; zero-work boots stay silent.
            if (jsonResult.registered() + jsonResult.identicalTwins() > 0) {
                LOG.log(System.Logger.Level.INFO, "[VINE] structural JSON: registered "
                    + jsonResult.registered() + ", identical " + jsonResult.identicalTwins());
            }
            registries.freeze();
            net.freezeAndSync();
            commands.freeze();
            // Store schemas must be registered before the schema registry freezes:
            // the session store's snapshot schema is engine-owned and needs no
            // consumer registration.
            SessionService.ensureStoreSchema();
            IdMapStore.ensureSchema();
            schemas.freeze();
            capabilities.freeze();
            sessions.freeze();
            extensions.freeze();
        });
        // Engine state install before any driver can bind (net-seam ordering rule)
        VoxelStorageBinding.engineRegistry(schemas);
        machine.advanceTo(EnginePhase.VINE_BOOT);
        List<VineDriver> drivers = new ArrayList<>();
        for (VineDriver driver : ServiceLoader.load(VineDriver.class)) {
            drivers.add(driver);
        }
        if (drivers.isEmpty()) {
            LOG.log(System.Logger.Level.INFO,
                "[VINE] no VineDriver service found — engine inert, phases will not advance (Minimal Footprint)");
            return;
        }
        if (drivers.size() > 1) {
            throw new BootFailureException("expected exactly one VineDriver service but found "
                + drivers.size() + ": " + drivers.stream().map(d -> d.getClass().getName()).toList());
        }
        VineDriver driver = drivers.get(0);
        VineDriver.CellInfo cell = Objects.requireNonNull(driver.cell(),
            () -> "driver " + driver.getClass().getName() + " returned null CellInfo");
        LOG.log(System.Logger.Level.INFO,
            "[VINE] driver bound: " + driver.getClass().getName()
                + " (loader=" + cell.loader() + ", dataVersion=" + cell.dataVersion() + ")");
        features = new FeatureMatrix(cell.features());
        LOG.log(System.Logger.Level.INFO, "[VINE] features " + features.ids());
        reportUnsafeScan();
        driver.bootstrap(new CoreDriverContext(machine, registries, bus, sessions, idMap));
    }

    @Override
    public EnginePhase phase() {
        EnginePhase current = machine.current();
        return current != null ? current : EnginePhase.VINE_BOOT;
    }

    @Override
    public Subscription onPhase(EnginePhase phase, Consumer<PhaseChange> handler) {
        return machine.onPhase(phase, handler);
    }

    @Override
    public EventBus events() {
        return bus;
    }

    @Override
    public boolean supports(Feature feature) {
        return features.supports(Objects.requireNonNull(feature, "feature"));
    }

    @Override
    public <D> void defineType(DescriptorType<D> type) {
        registries.defineType(type);
    }

    @Override
    public <D> Holder<D> register(DescriptorType<D> type, VineId id, D data) {
        return registries.register(type, id, data);
    }

    @Override
    public <D> Optional<Holder<D>> get(DescriptorType<D> type, VineId id) {
        return registries.get(type, id);
    }

    /**
     * The networking service (sub-05). Eagerly constructed and dormant with
     * zero consumers; it becomes live when a driver binds its
     * {@code NetTransport} during bootstrap.
     */
    @Override
    public VineNet net() {
        return net;
    }

    @Override
    public dev.vineengine.vine.net.VineBuf allocate() {
        return dev.vineengine.vine.internal.net.ByteArrayVineBuf.writable();
    }

    @Override
    public dev.vineengine.vine.net.VineBuf wrap(byte[] payload) {
        return dev.vineengine.vine.internal.net.ByteArrayVineBuf.wrap(payload);
    }

    /**
     * The command registration service (sub-06). Dormant with zero consumers —
     * no driver-side command is attached until a descriptor is registered and a
     * native dispatcher build pulls the snapshot.
     */
    /**
     * The command registration service (sub-06). Dormant with zero consumers —
     * no driver-side command is attached until a descriptor is registered and a
     * native dispatcher build pulls the snapshot.
     */
    @Override
    public VineCommands commands() {
        return commands;
    }

    /**
     * The schema registry (sub-03 Stage C). Dormant with zero consumers —
     * schema registration is a consumer-init act at {@code REGISTRIES_OPEN};
     * the registry freezes with everything else at {@code REGISTRIES_FROZEN}.
     */
    @Override
    public void registerSchema(VoxelSchema schema, List<VoxelDataFixer> fixers) {
        schemas.registerSchema(schema, fixers);
    }

    @Override
    public VoxelData create(VineId schemaId) {
        return schemas.create(schemaId);
    }

    /**
     * Attach-point access routes to the cell's storage driver; with no driver
     * bound (headless runtime, pre-Stage-D driver) the failure is explicit —
     * the engine never guesses storage.
     */
    @Override
    public VoxelData open(VoxelTarget target, VineId schemaId) {
        VoxelStorageDriver storage = VoxelStorageBinding.bound();
        if (storage == null) {
            throw new IllegalStateException(
                "no VoxelStorageDriver bound — attach-point access needs a cell driver (headless runtimes use VineData.create)");
        }
        return storage.open(target, schemaId);
    }

    @Override
    public byte[] encode(VoxelData tree) {
        return VoxelBlobCodec.save(tree);
    }

    @Override
    public VoxelData decode(byte[] blob) {
        return VoxelBlobCodec.load(blob, schemas);
    }

    @Override
    public byte[] encodeDelta(VoxelData tree, java.util.Set<String> paths) {
        return VoxelBlobCodec.saveDelta(tree, paths);
    }

    @Override
    public int applyDelta(VoxelData tree, byte[] delta) {
        return VoxelBlobCodec.applyDelta(tree, delta);
    }

    @Override
    public void registerNativeField(VineId schemaId, String path, String nativeComponentId) {
        NativeFields.register(schemaId, path, nativeComponentId);
    }

    // ------------------------------------------------------------------
    // CapabilityBackend (sub-04): delegate to the engine capability store.
    // ------------------------------------------------------------------

    @Override
    public <T> CapabilityType<T> register(CapabilityType<T> type) {
        return capabilities.register(type);
    }

    @Override
    public <T> void attach(CapabilityType<T> type, CapabilityScope scope,
            CapabilityProvider<T> provider) {
        capabilities.attach(type, scope, provider);
    }

    @Override
    public <T> Optional<T> find(CapabilityType<T> type, CapabilityTarget target) {
        return capabilities.find(type, target);
    }

    @Override
    public <T> Optional<T> findForeign(VineId nativeId, Class<T> apiClass,
            CapabilityTarget target) {
        return capabilities.findForeign(nativeId, apiClass, target);
    }

    @Override
    public void flush(CapabilityTarget target) {
        capabilities.flush(target);
    }

    @Override
    public void invalidate(CapabilityTarget target) {
        capabilities.invalidate(target);
    }

    @Override
    public <T> void applyClone(CapabilityType<T> type, CapabilityTarget oldTarget,
            CapabilityTarget newTarget) {
        capabilities.applyClone(type, oldTarget, newTarget);
    }

    // ------------------------------------------------------------------
    // SessionBackend (sub-14): the service is the manager.
    // ------------------------------------------------------------------

    @Override
    public void registerFactory(SessionFactory factory) {
        sessions.registerFactory(factory);
    }

    @Override
    public SessionManager manager() {
        return sessions;
    }

    // ------------------------------------------------------------------
    // ConfigBackend (sub-01 Stage E): the config service.
    // ------------------------------------------------------------------

    @Override
    public String getString(String key, String defaultValue) {
        return config.getString(key, defaultValue);
    }

    @Override
    public int getInt(String key, int defaultValue) {
        return config.getInt(key, defaultValue);
    }

    @Override
    public long getLong(String key, long defaultValue) {
        return config.getLong(key, defaultValue);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        return config.getBoolean(key, defaultValue);
    }

    @Override
    public void reload() {
        config.reload();
    }

    @Override
    public void onReload(Runnable listener) {
        config.onReload(listener);
    }

    @Override
    public ExtensionPoints extensions() {
        return extensions;
    }

    @Override
    public void setMissingContentPolicy(String namespace, dev.vineengine.vine.registry.MissingContentPolicy policy) {
        registries.setMissingContentPolicy(namespace, policy);
    }

    @Override
    public dev.vineengine.vine.registry.MissingContentPolicy missingContentPolicyFor(String namespace) {
        return registries.missingContentPolicyFor(namespace);
    }

    @Override
    public boolean isRegistered(String key) {
        return registries.isRegistered(key);
    }

    @Override
    public java.util.Map<String, Integer> idMap() {
        return idMap.mappings();
    }

    /**
     * Audits consumer jars for @VineUnsafe-vs-manifest consistency (sub-01 §5.1).
     * Best-effort: violations are logged, never fatal — the scan must not break
     * a boot it merely audits.
     */
    private void reportUnsafeScan() {
        try {
            UnsafeScan.Report report = UnsafeScan.scanClasspath(
                System.getProperty("java.class.path", ""));
            for (UnsafeScan.Violation violation : report.violations()) {
                LOG.log(System.Logger.Level.WARNING, "[VINE] Vine-Unsafe mismatch — " + violation.describe());
            }
            LOG.log(System.Logger.Level.INFO, "[VINE] unsafe scan: " + report.scanned().size()
                + " entries clean, " + report.violations().size() + " mismatch(es)"
                + (report.skipped().isEmpty() ? "" : ", " + report.skipped().size() + " unreadable"));
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] unsafe scan failed (boot continues): " + e);
        }
    }
}
