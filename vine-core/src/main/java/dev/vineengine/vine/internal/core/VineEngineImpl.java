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
import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelDataFixer;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.internal.AnimationBackend;
import dev.vineengine.vine.internal.CapabilityBackend;
import dev.vineengine.vine.internal.CommandBackend;
import dev.vineengine.vine.internal.ConfigBackend;
import dev.vineengine.vine.internal.BrainBackend;
import dev.vineengine.vine.internal.EntityBackend;
import dev.vineengine.vine.internal.NetBackend;
import dev.vineengine.vine.internal.RegistryBackend;
import dev.vineengine.vine.internal.SessionBackend;
import dev.vineengine.vine.internal.VoxelBackend;
import dev.vineengine.vine.internal.WorldBackend;
import dev.vineengine.vine.internal.capability.CapabilityStore;
import dev.vineengine.vine.internal.command.CommandService;
import dev.vineengine.vine.internal.content.BehaviorDispatch;
import dev.vineengine.vine.internal.content.BlockStateTable;
import dev.vineengine.vine.internal.config.ConfigService;
import dev.vineengine.vine.internal.data.NativeFields;
import dev.vineengine.vine.internal.data.SchemaRegistry;
import dev.vineengine.vine.internal.data.VoxelBlobCodec;
import dev.vineengine.vine.internal.data.VoxelStorageBinding;
import dev.vineengine.vine.internal.animation.AnimationAssetParser;
import dev.vineengine.vine.internal.animation.PoseEvaluator;
import dev.vineengine.vine.internal.registry.IdMapStore;
import dev.vineengine.vine.internal.command.CommandJsonLoader;
import dev.vineengine.vine.internal.registry.StructuralJsonLoader;
import dev.vineengine.vine.internal.net.VineNetImpl;
import dev.vineengine.vine.internal.registry.DescriptorStore;
import dev.vineengine.vine.internal.session.SessionService;
import dev.vineengine.vine.internal.brain.BrainImpl;
import dev.vineengine.vine.internal.entity.EntityBinding;
import dev.vineengine.vine.internal.world.EngineWorldView;
import dev.vineengine.vine.internal.world.WorldViewBinding;
import dev.vineengine.vine.internal.spi.VineDriver;
import dev.vineengine.vine.internal.spi.VoxelStorageDriver;
import dev.vineengine.vine.net.VineNet;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.brain.VineBrain;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorld;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineRegistries;
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
        VoxelBackend, CapabilityBackend, SessionBackend, ConfigBackend, WorldBackend, EntityBackend, BrainBackend,
        AnimationBackend, dev.vineengine.vine.internal.PartBackend, dev.vineengine.vine.internal.CombatBackend,
        dev.vineengine.vine.internal.QuestBackend, dev.vineengine.vine.internal.CutsceneBackend {

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
    private final dev.vineengine.vine.internal.combat.CombatPipelineImpl combat =
        new dev.vineengine.vine.internal.combat.CombatPipelineImpl();
    private final dev.vineengine.vine.internal.quest.QuestService quests =
        new dev.vineengine.vine.internal.quest.QuestService();
    private final dev.vineengine.vine.internal.cutscene.CutsceneRuntime cutscenes =
        new dev.vineengine.vine.internal.cutscene.CutsceneRuntime();

    VineEngineImpl() {
        // Quests are a design registry, so the service lists them through the store rather
        // than the runtime id map; installed here so a consumer init that registers a quest
        // and immediately asks about it sees a consistent registry.
        quests.registry(registries);
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
        registries.defineType(VineContent.ENTITY_TYPE);
        // Combat descriptors (sub-10 Stage A): structural, like every kind whose timing is
        // read against animation clips.
        registries.defineType(VineContent.ACTION_TYPE);
        registries.defineType(VineContent.ATTACK_TYPE);
        // Quest kinds (sub-15): design registries, so a pack author edits and reloads them.
        registries.defineType(dev.vineengine.vine.quest.VineQuests.CHAPTER_TYPE);
        registries.defineType(dev.vineengine.vine.quest.VineQuests.QUEST_TYPE);
        registries.defineType(VineContent.CUTSCENE_TYPE);
        // Client 2D descriptors (sub-16 Stage A): structural, laid out by the engine.
        registries.defineType(VineContent.SCREEN_TYPE);
        registries.defineType(VineContent.HUD_LAYER_TYPE);
        // Structural JSON must be complete before the first cell snapshots the view
        // (see DescriptorStore#beforeStructuralSnapshot): on Fabric the native
        // registries freeze during mod init, so a JSON-authored descriptor that
        // arrived at the engine's own freeze phase would be materialized by nobody.
        registries.beforeStructuralSnapshot(this::loadStructuralJsonOnce);
        machine.onPhase(EnginePhase.REGISTRIES_FROZEN, change -> {
            // Structural JSON authoring (sub-02 Stage F) lands before the freeze; a
            // cell that materialized earlier already ran this through the store's
            // snapshot hook, and a headless run reaches it here.
            loadStructuralJsonOnce();
            registries.freeze();
            // Stage C's engine-side half of "a block with no ticking block entity
            // installs no ticker": the number a cell's own installed-ticker count
            // must equal, printed where boot logs carry it on every cell.
            LOG.log(System.Logger.Level.INFO, "[VINE] behaviors: ticking block entities="
                + BehaviorDispatch.tickingBlockCount());
            net.freezeAndSync();
            commands.freeze();
            // Store schemas must be registered before the schema registry freezes:
            // the session store's snapshot schema is engine-owned and needs no
            // consumer registration.
            SessionService.ensureStoreSchema();
            IdMapStore.ensureSchema();
            // Part state (sub-08 Stage D) is engine data stored in the actor's own
            // tree: its schema is engine-owned and must exist before the freeze.
            dev.vineengine.vine.internal.entity.PartRuntime.ensureSchema();
            dev.vineengine.vine.internal.quest.QuestService.ensureSchema();
            // The engine's own objective and reward kinds, registered before any consumer can
            // collide with them.
            quests.registerBuiltins();
            // The brain loop asks the pipeline whether an actor is frozen (sub-10 hitstop);
            // the dependency points engine-side, so a headless run with no pipeline still
            // answers "never frozen".
            dev.vineengine.vine.internal.entity.EntityRuntime.hitstopSource(
                ref -> combat.stateImpl().hitstopTicks(
                    new dev.vineengine.vine.combat.CombatActorRef.Actor(ref)));
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
        driver.bootstrap(new CoreDriverContext(machine, registries, bus, sessions, idMap, quests));
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

    /** Guards {@link #loadStructuralJsonOnce}: the pass registers entries, so once is enough. */
    private boolean structuralJsonLoaded;

    /**
     * Reads every structural JSON descriptor once per JVM session (sub-02 Stage F).
     * Idempotent, because two callers legitimately race for it: a cell asks for the
     * structural view during its materialization window, and the freeze phase reaches
     * it too in runs that never materialize anything.
     */
    private synchronized void loadStructuralJsonOnce() {
        if (structuralJsonLoaded) {
            return;
        }
        structuralJsonLoaded = true;
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
    }

    @Override
    public <D> void defineType(DescriptorType<D> type) {
        registries.defineType(type);
    }

    @Override
    public <D> Holder<D> register(DescriptorType<D> type, VineId id, D data) {
        // Block descriptors carry a flattened state model whose budget is a hard
        // registration-time limit (sub-07 Stage B): an over-budget model cannot be
        // materialized by any cell, so it is rejected here — naming the product
        // terms — rather than at a cell's boot, where the author is not looking.
        if (data instanceof BlockDescriptor block) {
            BlockStateTable.validate(block);
            BehaviorDispatch.noteRegistered(block);
        }
        return registries.register(type, id, data);
    }

    @Override
    public VineWorld world(VineId dimensionId) {
        return new EngineWorldView(dimensionId, WorldViewBinding.bound());
    }

    @Override
    public java.util.Optional<dev.vineengine.vine.entity.VineEntityRef> spawn(VineId entityId, VineWorld world,
            Vec3 position) {
        // The engine validates identity before the cell is asked: a cell must never
        // have to answer for content the engine does not know, and "unregistered id"
        // is a caller bug rather than a world condition.
        if (VineRegistries.<dev.vineengine.vine.entity.EntityDescriptor>get(
                dev.vineengine.vine.content.VineContent.ENTITY_TYPE, entityId).isEmpty()) {
            throw new IllegalArgumentException("no entity descriptor is registered under " + entityId
                + " — spawning unregistered content is a caller bug");
        }
        // The engine chooses the instance id (sub-08 Stage C): identity must not depend
        // on a cell's own numbering, and two entities of one descriptor are two actors.
        java.util.UUID instance = java.util.UUID.randomUUID();
        return EntityBinding.bound().spawn(entityId, world.id(), position, instance)
            ? java.util.Optional.of(new dev.vineengine.vine.entity.VineEntityRef(entityId, instance))
            : java.util.Optional.empty();
    }

    // ---- CutsceneBackend (sub-23): server-driven cinematics ----------------------

    @Override
    public void play(VineId cutscene, java.util.Set<java.util.UUID> viewers) {
        cutscenes.play(cutscene, viewers);
    }

    @Override
    public void stop() {
        cutscenes.stop();
    }

    @Override
    public boolean playing() {
        return cutscenes.playing();
    }

    @Override
    public java.util.Optional<VineId> current() {
        return cutscenes.current();
    }

    @Override
    public java.util.Set<java.util.UUID> viewers() {
        return cutscenes.viewers();
    }

    @Override
    public void tickCutscenes() {
        cutscenes.tickCutscenes();
    }

    @Override
    public java.util.Optional<dev.vineengine.vine.cutscene.CutsceneFrame> frame() {
        return cutscenes.frame();
    }

    @Override
    public dev.vineengine.vine.cutscene.CutsceneFrame evaluate(VineId cutscene, long tick) {
        return cutscenes.evaluate(cutscene, tick);
    }

    @Override
    public void addFrameListener(java.util.function.BiConsumer<java.util.UUID,
            dev.vineengine.vine.cutscene.CutsceneFrame> listener) {
        cutscenes.addFrameListener(listener);
    }

    /** The cutscene runtime itself, for the frame transport a cell drives (sub-23 Stage B). */
    public dev.vineengine.vine.internal.cutscene.CutsceneRuntime cutsceneRuntime() {
        return cutscenes;
    }

    // ---- QuestBackend (sub-15): progress, claims and progression -----------------

    @Override
    public <P> void registerObjectiveType(VineId id, dev.vineengine.vine.quest.ObjectiveType<P> type) {
        quests.registerObjectiveType(id, type);
    }

    @Override
    public <P> void registerRewardType(VineId id, dev.vineengine.vine.quest.RewardType<P> type) {
        quests.registerRewardType(id, type);
    }

    @Override
    public void fireEvent(VineId eventType, java.util.UUID player, dev.vineengine.vine.data.VoxelData payload) {
        quests.fireEvent(eventType, player, payload);
    }

    @Override
    public dev.vineengine.vine.quest.QuestProgress progress(java.util.UUID player, VineId quest) {
        return quests.progress(player, quest);
    }

    @Override
    public java.util.List<dev.vineengine.vine.quest.QuestProgress> active(java.util.UUID player) {
        return quests.active(player);
    }

    @Override
    public boolean start(java.util.UUID player, VineId quest) {
        return quests.start(player, quest);
    }

    @Override
    public java.util.List<VineId> claim(java.util.UUID player, VineId quest) {
        return quests.claim(player, quest);
    }

    @Override
    public void tick() {
        quests.tick();
    }

    @Override
    public boolean abandon(java.util.UUID player, VineId quest) {
        return quests.abandon(player, quest);
    }

    @Override
    public void addCompletionListener(java.util.function.BiConsumer<java.util.UUID, VineId> listener) {
        quests.addCompletionListener(listener);
    }

    @Override
    public int xp(java.util.UUID player) {
        return quests.xp(player);
    }

    @Override
    public int addXp(java.util.UUID player, int amount) {
        return quests.addXp(player, amount);
    }

    @Override
    public int skill(java.util.UUID player, VineId skill) {
        return quests.skill(player, skill);
    }

    @Override
    public void setSkill(java.util.UUID player, VineId skill, int level) {
        quests.setSkill(player, skill, level);
    }

    /** The quest service itself, for the per-tick flush a cell drives (sub-15). */
    public dev.vineengine.vine.internal.quest.QuestService questService() {
        return quests;
    }

    // ---- CombatBackend (sub-10 Stage C): the server-authoritative pipeline -------

    @Override
    public dev.vineengine.vine.combat.CombatResult strike(dev.vineengine.vine.combat.CombatActorRef attacker,
            dev.vineengine.vine.entity.VineEntityRef target, VineId attackId, double baseDamage,
            dev.vineengine.vine.world.Vec3 attackerPosition, float attackerYawDegrees) {
        return combat.strike(attacker, target, attackId, baseDamage, attackerPosition, attackerYawDegrees);
    }

    @Override
    public dev.vineengine.vine.combat.CombatState state() {
        return combat.state();
    }

    @Override
    public void addModifier(dev.vineengine.vine.combat.CombatModifier modifier) {
        combat.addModifier(modifier);
    }

    @Override
    public int modifierCount() {
        return combat.modifierCount();
    }

    /** The pipeline's state object, for the per-tick decay a cell drives (sub-10 Stage D). */
    public dev.vineengine.vine.internal.combat.CombatPipelineImpl combatPipeline() {
        return combat;
    }

    // ---- PartBackend (sub-08 Stage D): multipart hosting ------------------------

    @Override
    public void attach(dev.vineengine.vine.entity.VineEntityRef ref,
            dev.vineengine.vine.animation.AnimationAsset asset, String clip) {
        dev.vineengine.vine.internal.entity.PartRuntime.attach(ref, asset, clip);
    }

    @Override
    public void detachParts(dev.vineengine.vine.entity.VineEntityRef ref) {
        dev.vineengine.vine.internal.entity.PartRuntime.detach(ref);
    }

    @Override
    public boolean isHosted(dev.vineengine.vine.entity.VineEntityRef ref) {
        return dev.vineengine.vine.internal.entity.PartRuntime.isHosted(ref);
    }

    @Override
    public void play(dev.vineengine.vine.entity.VineEntityRef ref, String clip, long tick) {
        dev.vineengine.vine.internal.entity.PartRuntime.play(ref, clip, tick);
    }

    @Override
    public java.util.List<dev.vineengine.vine.entity.PartState> parts(
            dev.vineengine.vine.entity.VineEntityRef ref) {
        return dev.vineengine.vine.internal.entity.PartRuntime.parts(ref);
    }

    @Override
    public java.util.Optional<dev.vineengine.vine.entity.PartState> part(
            dev.vineengine.vine.entity.VineEntityRef ref, String name) {
        return dev.vineengine.vine.internal.entity.PartRuntime.part(ref, name);
    }

    @Override
    public java.util.Optional<dev.vineengine.vine.animation.OrientedBox> box(
            dev.vineengine.vine.entity.VineEntityRef ref, String name, dev.vineengine.vine.world.Vec3 position,
            float yawDegrees) {
        return dev.vineengine.vine.internal.entity.PartRuntime.box(ref, name, position, yawDegrees);
    }

    @Override
    public java.util.Optional<dev.vineengine.vine.animation.SkeletonPose> pose(
            dev.vineengine.vine.entity.VineEntityRef ref) {
        return dev.vineengine.vine.internal.entity.PartRuntime.pose(ref);
    }

    @Override
    public java.util.Optional<dev.vineengine.vine.entity.VineParts.PartHit> applyHit(
            dev.vineengine.vine.entity.VineEntityRef ref, String name, double amount,
            dev.vineengine.vine.registry.VineId damageType) {
        return dev.vineengine.vine.internal.entity.PartRuntime.applyHit(ref, name, amount, damageType);
    }

    @Override
    public boolean consumeFlinch(dev.vineengine.vine.entity.VineEntityRef ref) {
        return dev.vineengine.vine.internal.entity.PartRuntime.consumeFlinch(ref);
    }

    @Override
    public void attach(dev.vineengine.vine.entity.VineEntityRef ref, VineBrain brain) {
        dev.vineengine.vine.internal.entity.EntityRuntime.attach(ref, brain);
    }

    @Override
    public void detach(dev.vineengine.vine.entity.VineEntityRef ref) {
        dev.vineengine.vine.internal.entity.EntityRuntime.detach(ref);
    }

    @Override
    public VineBrain brain(VineId actorId, dev.vineengine.vine.data.VoxelData memory) {
        // the brain's memory IS the caller's tree (sub-08 Stage B): one identity, so
        // the storage layer persists exactly what the behaviour wrote.
        return new BrainImpl(actorId, memory);
    }

    // ------------------------------------------------------------------
    // AnimationBackend (sub-09 Stage B): the headless pose evaluator.
    //
    // Pure functions over a parsed asset — no clock, no render frame, no client
    // type, and no engine state, so the same call answers identically on every
    // cell and in the plain-JVM fixture harness (sub-09 §2/§4).
    // ------------------------------------------------------------------

    @Override
    public dev.vineengine.vine.animation.AnimationAsset parse(String assetJson) {
        return AnimationAssetParser.parse(assetJson);
    }

    @Override
    public dev.vineengine.vine.animation.SkeletonPose pose(
            dev.vineengine.vine.animation.AnimationAsset asset, String clip, double seconds) {
        return PoseEvaluator.pose(asset, clip, seconds);
    }

    @Override
    public dev.vineengine.vine.animation.TimingWindows windows(
            dev.vineengine.vine.animation.AnimationAsset asset, String clip) {
        return PoseEvaluator.windows(asset, clip);
    }

    @Override
    public dev.vineengine.vine.animation.OrientedBox partBox(dev.vineengine.vine.animation.SkeletonPose pose,
            dev.vineengine.vine.entity.PartDescriptor part, Vec3 actorPosition, float actorYawDegrees) {
        return PoseEvaluator.partBox(pose, part, actorPosition, actorYawDegrees);
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
