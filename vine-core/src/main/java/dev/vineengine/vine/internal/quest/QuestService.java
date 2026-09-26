package dev.vineengine.vine.internal.quest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.internal.QuestBackend;
import dev.vineengine.vine.quest.ObjectiveBehavior;
import dev.vineengine.vine.quest.ObjectiveInstance;
import dev.vineengine.vine.quest.ObjectiveType;
import dev.vineengine.vine.quest.ProgressState;
import dev.vineengine.vine.quest.QuestDescriptor;
import dev.vineengine.vine.quest.QuestProgress;
import dev.vineengine.vine.quest.RewardInstance;
import dev.vineengine.vine.quest.RewardType;
import dev.vineengine.vine.quest.VineQuests;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

/**
 * The engine's quest service (sub-15): progress counters, the state machine, reward claims
 * and progression, all stored in the player's own engine tree.
 *
 * <p><b>Where progress lives.</b> In the player's tree, under {@code quests.<id>.*} — the
 * same place engine data lives everywhere else, so a reload restores progress without a
 * mechanism of its own. The tree itself comes from the running cell through
 * {@link Storage}: only a cell has the native player, exactly as with a part's host. With no
 * cell (headless harness, a fixture) the service keeps one tree per player in memory and the
 * same code path runs unchanged.
 *
 * <p><b>Batching is not an optimisation, it is the contract.</b> Events queue during a tick
 * and flush in one pass, coalesced by type and payload: a beast dying to a whirlwind of hits
 * advances a kill objective by the number of kills, not by the number of event packets, and
 * a player's quest state is written once per tick rather than once per event.
 */
public final class QuestService implements QuestBackend {

    /** The schema a player's quest tree is stored under. */
    public static final VineId SCHEMA = VineId.parse("vine:quest");

    /** Where a player's quest tree comes from. Installed by a cell; in-memory by default. */
    public interface Storage {

        /** The player's engine tree — the same instance every call, or writes would be lost. */
        VoxelData playerState(UUID player);
    }

    /** The store key a world's quest progress lives under, next to the session store. */
    public static final String STORE_KEY = "quests";

    private static volatile Storage storage = new MemoryStorage();
    private static boolean schemaRegistered;

    /** The headless default: one tree per player, kept for the process's lifetime. */
    private static final class MemoryStorage implements Storage {

        private final Map<UUID, VoxelData> trees = new ConcurrentHashMap<>();

        @Override
        public VoxelData playerState(UUID player) {
            return trees.computeIfAbsent(player, key -> VineData.create(SCHEMA));
        }
    }

    /** Installs the cell's storage; {@code null} restores the in-memory default. */
    public static void storage(Storage installed) {
        storage = installed == null ? new MemoryStorage() : installed;
    }

    /**
     * The world's quest tree: every player's progress in one engine-owned tree, persisted
     * through the same per-world store the session and id-map state use. This is the default
     * because it is the one that works without a player online — a reload test has no client,
     * and progression that only survives while someone is logged in is not progression.
     */
    private static final class WorldStoreStorage implements Storage {

        private final VoxelData root;

        WorldStoreStorage(VoxelData root) {
            this.root = root;
        }

        @Override
        public VoxelData playerState(UUID player) {
            String path = playerPath(player);
            if (!root.contains(path)) {
                // getCompound on a missing path returns a detached node: attaching one first is
                // what makes the first write land in the tree.
                root.put(path, VineData.create(SCHEMA));
            }
            return root.getCompound(path);
        }

        VoxelData root() {
            return root;
        }
    }

    private WorldStoreStorage worldStorage;

    /**
     * Mounts the world's persisted quest progress: called with the other stores when a cell
     * mounts its world store. A stored blob that cannot be read is reported and treated as
     * "no progress yet" — refusing to boot a world over an unreadable side store would be worse
     * than the loss the message describes.
     */
    public synchronized void mount(dev.vineengine.vine.internal.spi.WorldStoreSpi store) {
        VoxelData root = VineData.create(SCHEMA);
        byte[] blob = store.load(STORE_KEY);
        if (blob != null && blob.length > 0) {
            try {
                root = VineData.decode(blob);
            } catch (RuntimeException broken) {
                LOG.log(System.Logger.Level.WARNING, "[VINE] quests: stored progress could not be read ("
                    + broken.getMessage() + ") — starting empty");
                root = VineData.create(SCHEMA);
            }
        }
        worldStorage = new WorldStoreStorage(root);
        storage = worldStorage;
    }

    /** Writes the world's quest progress through the store. */
    public synchronized void flush(dev.vineengine.vine.internal.spi.WorldStoreSpi store) {
        if (worldStorage == null) {
            return;
        }
        store.save(STORE_KEY, VineData.encode(worldStorage.root()));
    }

    private static String playerPath(UUID player) {
        return "players." + player.toString().replace("-", "");
    }

    private static final System.Logger LOG = System.getLogger("vine.quests");

    /** Registers the quest schema; must run before the schema registry freezes. */
    public static synchronized void ensureSchema() {
        if (schemaRegistered) {
            return;
        }
        VineData.registerSchema(new VoxelSchema(SCHEMA, 1, Codec.unit(null)), List.of());
        schemaRegistered = true;
    }

    /**
     * Where registered quests are listed from. Quests are a design registry, so they are not
     * in the runtime id map — a quest's kind rides a datapack registry, and the store is the
     * only object that knows both classes of entry. Injected at engine construction.
     */
    private volatile dev.vineengine.vine.internal.registry.DescriptorStore store;

    private final Map<VineId, ObjectiveType<?>> objectiveTypes = new ConcurrentHashMap<>();
    private final Map<VineId, RewardType<?>> rewardTypes = new ConcurrentHashMap<>();
    private final List<BiConsumer<UUID, VineId>> completionListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<UUID, List<Event>> pending = new ConcurrentHashMap<>();

    /** One queued event: what happened, and its payload. */
    private record Event(VineId type, VoxelData payload) {
    }

    /** The parameters of {@code vine:xp}: how much experience the quest pays. */
    public record XpParams(int amount) {

        /** Single source of truth for the {@code vine:xp} authoring form. */
        public static final Codec<XpParams> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder
            .create(instance -> instance.group(
                Codec.INT.fieldOf("amount").forGetter(XpParams::amount)
            ).apply(instance, XpParams::new));
    }

    /** The parameters of {@code vine:skill}: which skill, and up to which level. */
    public record SkillParams(VineId skill, int level) {

        /** Single source of truth for the {@code vine:skill} authoring form. */
        public static final Codec<SkillParams> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder
            .create(instance -> instance.group(
                VineId.CODEC.fieldOf("skill").forGetter(SkillParams::skill),
                Codec.INT.fieldOf("level").forGetter(SkillParams::level)
            ).apply(instance, SkillParams::new));
    }

    /** Installs the registry store quests are listed from. */
    public void registry(dev.vineengine.vine.internal.registry.DescriptorStore registryStore) {
        this.store = registryStore;
    }

    /**
     * Every registered quest, in a stable order, whichever class its entry lives in.
     *
     * <p>Quests are a design registry, and its entries arrive from datapacks at world load —
     * which is exactly why they cannot be listed from the runtime id map, and why this reads
     * the store's design entries by registry id instead.
     */
    private List<QuestDescriptor> registeredQuests() {
        dev.vineengine.vine.internal.registry.DescriptorStore current = store;
        if (current == null) {
            return List.of();
        }
        Map<VineId, QuestDescriptor> found = new java.util.TreeMap<>();
        VineId registryId = VineQuests.QUEST_TYPE.registryId();
        // Both lists a design kind keeps: what consumers registered, and what datapacks
        // loaded. Reading one of them is the bug that made the first campaign beat count
        // nothing.
        for (Map.Entry<VineId, Object> entry : current.registeredEntries(registryId).entrySet()) {
            // Consumer registrations are stored as holders (so reads can resolve runtime ids);
            // datapack entries are stored as plain values. Accepting only one shape is how a
            // quest service ends up insisting no quests exist while the registry disagrees.
            Object candidate = entry.getValue() instanceof dev.vineengine.vine.registry.Holder<?> holder
                ? holder.value()
                : entry.getValue();
            if (candidate instanceof QuestDescriptor quest) {
                found.put(entry.getKey(), quest);
            }
        }
        for (Map.Entry<VineId, Object> entry : current.designEntries(registryId).entrySet()) {
            if (entry.getValue() instanceof QuestDescriptor quest) {
                found.put(entry.getKey(), quest);
            }
        }
        return List.copyOf(found.values());
    }

    /** Registers the engine's own objective and reward kinds. */
    public void registerBuiltins() {
        // vine:count — the generic shape: an event's payload carries an amount, or counts as one.
        registerObjectiveType(VineId.of("vine", "count"), new ObjectiveType<>(Codec.unit(0),
            (params, event) -> event.contains("amount") ? Math.max(0, event.getInt("amount")) : 1));
        // vine:xp / vine:skill — the two progression rewards a quest can pay.
        registerRewardType(VineId.of("vine", "xp"), new RewardType<>(XpParams.CODEC, (player, params) -> {
            addXp(player, params.amount());
            return "xp+" + params.amount() + " total=" + xp(player)
                + " level=" + VineQuests.levelForXp(xp(player));
        }));
        registerRewardType(VineId.of("vine", "skill"), new RewardType<>(SkillParams.CODEC, (player, params) -> {
            setSkill(player, params.skill(), Math.max(skill(player, params.skill()), params.level()));
            return "skill " + params.skill() + "=" + skill(player, params.skill());
        }));
    }

    @Override
    public <P> void registerObjectiveType(VineId id, ObjectiveType<P> type) {
        ObjectiveType<?> previous = objectiveTypes.putIfAbsent(id, type);
        if (previous != null) {
            throw new IllegalStateException("objective type " + id + " is already registered — two types under one"
                + " id cannot be ordered");
        }
    }

    @Override
    public <P> void registerRewardType(VineId id, RewardType<P> type) {
        RewardType<?> previous = rewardTypes.putIfAbsent(id, type);
        if (previous != null) {
            throw new IllegalStateException("reward type " + id + " is already registered");
        }
    }

    @Override
    public void fireEvent(VineId eventType, UUID player, VoxelData payload) {
        pending.computeIfAbsent(player, key -> new java.util.ArrayList<>(4)).add(new Event(eventType,
            payload.copy()));
    }

    @Override
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        Map<UUID, List<Event>> batch = new LinkedHashMap<>(pending);
        pending.clear();
        for (Map.Entry<UUID, List<Event>> entry : batch.entrySet()) {
            flush(entry.getKey(), entry.getValue());
        }
    }

    private void flush(UUID player, List<Event> events) {
        // Coalesce: same type and same payload collapse into one event with a count, so a
        // flood costs one pass over the player's quests.
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Event> first = new LinkedHashMap<>();
        for (Event event : events) {
            String key = event.type() + "|" + java.util.Arrays.toString(VineData.encode(event.payload()));
            counts.computeIfAbsent(key, ignored -> new int[1])[0]++;
            first.putIfAbsent(key, event);
        }
        VoxelData tree = storage.playerState(player);
        for (Map.Entry<String, int[]> entry : counts.entrySet()) {
            Event event = first.get(entry.getKey());
            int occurrences = entry.getValue()[0];
            boolean advanced = false;
            for (QuestDescriptor quest : startedQuests(tree)) {
                QuestProgress progress = read(tree, quest);
                if (progress.state() != ProgressState.ACTIVE) {
                    continue;
                }
                QuestProgress updated = progress;
                for (ObjectiveInstance objective : quest.objectives()) {
                    if (!listensFor(objective, event.type())) {
                        continue;
                    }
                    int increment = advance(objective, event.payload()) * occurrences;
                    if (increment <= 0) {
                        continue;
                    }
                    int next = Math.min(objective.count(), updated.count(objective.id()) + increment);
                    updated = updated.withCount(objective.id(), next);
                    advanced = true;
                }
                if (advanced && !updated.equals(progress)) {
                    ProgressState state = quest.satisfiedBy(updated) ? ProgressState.COMPLETED : ProgressState.ACTIVE;
                    write(tree, updated.withState(state));
                    if (state == ProgressState.COMPLETED) {
                        for (BiConsumer<UUID, VineId> listener : completionListeners) {
                            listener.accept(player, quest.id());
                        }
                    }
                }
            }
        }
    }

    @Override
    public QuestProgress progress(UUID player, VineId quest) {
        QuestDescriptor descriptor = descriptor(quest);
        if (descriptor == null) {
            throw new IllegalArgumentException("no quest is registered under " + quest);
        }
        return read(storage.playerState(player), descriptor);
    }

    @Override
    public List<QuestProgress> active(UUID player) {
        List<QuestProgress> out = new ArrayList<>();
        for (QuestDescriptor quest : startedQuests(storage.playerState(player))) {
            QuestProgress progress = read(storage.playerState(player), quest);
            if (progress.state() == ProgressState.ACTIVE || progress.state() == ProgressState.COMPLETED) {
                out.add(progress);
            }
        }
        return List.copyOf(out);
    }

    @Override
    public boolean start(UUID player, VineId quest) {
        QuestDescriptor descriptor = descriptor(quest);
        if (descriptor == null) {
            throw new IllegalArgumentException("no quest is registered under " + quest);
        }
        VoxelData tree = storage.playerState(player);
        QuestProgress progress = read(tree, descriptor);
        if (progress.state() != ProgressState.LOCKED) {
            return progress.state() == ProgressState.ACTIVE || progress.state() == ProgressState.COMPLETED;
        }
        for (VineId dependency : descriptor.dependencies()) {
            QuestDescriptor dependencyDescriptor = descriptor(dependency);
            if (dependencyDescriptor == null) {
                throw new IllegalStateException("quest " + quest + " depends on " + dependency
                    + ", which is not registered");
            }
            if (read(tree, dependencyDescriptor).state() != ProgressState.REWARDS_CLAIMED) {
                return false;
            }
        }
        write(tree, progress.withState(ProgressState.ACTIVE));
        return true;
    }

    @Override
    public List<VineId> claim(UUID player, VineId quest) {
        QuestDescriptor descriptor = descriptor(quest);
        if (descriptor == null) {
            throw new IllegalArgumentException("no quest is registered under " + quest);
        }
        VoxelData tree = storage.playerState(player);
        QuestProgress progress = read(tree, descriptor);
        if (!progress.claimable()) {
            return List.of();
        }
        List<VineId> granted = new ArrayList<>();
        for (int index = 0; index < descriptor.rewards().size(); index++) {
            if (progress.rewardsClaimed().contains(index)) {
                continue;
            }
            RewardInstance reward = descriptor.rewards().get(index);
            // Record first, pay second: a crash between the two loses a reward rather than
            // granting it twice.
            tree.put(claimPath(quest, index), (byte) 1);
            granted.add(reward.type());
            grant(reward, player);
        }
        write(tree, read(tree, descriptor).withState(ProgressState.REWARDS_CLAIMED));
        return List.copyOf(granted);
    }

    @Override
    public boolean abandon(UUID player, VineId quest) {
        VoxelData tree = storage.playerState(player);
        if (!tree.contains("quests." + quest)) {
            return false;
        }
        tree.remove("quests." + quest);
        return true;
    }

    @Override
    public void addCompletionListener(BiConsumer<UUID, VineId> listener) {
        completionListeners.add(listener);
    }

    @Override
    public int xp(UUID player) {
        return storage.playerState(player).getInt("xp");
    }

    @Override
    public int addXp(UUID player, int amount) {
        VoxelData tree = storage.playerState(player);
        int total = Math.max(0, tree.getInt("xp") + amount);
        tree.put("xp", total);
        return VineQuests.levelForXp(total);
    }

    @Override
    public int skill(UUID player, VineId skill) {
        return storage.playerState(player).getInt("skills." + skill);
    }

    @Override
    public void setSkill(UUID player, VineId skill, int level) {
        storage.playerState(player).put("skills." + skill, Math.max(0, level));
    }

    /** How many objective kinds and reward kinds are registered — a boot-log number. */
    public String registeredSummary() {
        return "objectiveTypes=" + objectiveTypes.size() + " rewardTypes=" + rewardTypes.size()
            + " quests=" + countQuests();
    }

    // ------------------------------------------------------------------ internals

    private int countQuests() {
        return registeredQuests().size();
    }

    private void grant(RewardInstance reward, UUID player) {
        RewardType<?> type = rewardTypes.get(reward.type());
        if (type == null) {
            throw new IllegalStateException("reward type " + reward.type() + " is not registered — a quest cannot"
                + " pay a reward nothing knows how to grant");
        }
        grantUnchecked(type, reward, player);
    }

    @SuppressWarnings("unchecked")
    private static <P> void grantUnchecked(RewardType<P> type, RewardInstance reward, UUID player) {
        P params = parse(type, reward.params(), reward.type());
        type.grant().grant(player, params);
    }

    /** One authored JSON value through a registered type's own codec. */
    private static <P> P parse(RewardType<P> type, com.google.gson.JsonElement json, VineId what) {
        return type.paramsCodec().parse(com.mojang.serialization.JsonOps.INSTANCE, json)
            .result().orElseThrow(() -> new IllegalStateException("reward " + what
                + " parameters did not parse — the descriptor's JSON and the registered codec disagree"));
    }

    /**
     * Whether an objective watches the event that just happened.
     *
     * <p>An objective's <em>type</em> is its kind ("count these"), not the thing it counts —
     * so the thing it counts is declared in its own parameters, as the event id a game fires
     * ({@code {"event": "mymod:kill"}}). The engine reads that one field from the authored
     * JSON rather than from the type's codec, because the codec belongs to the type and the
     * engine has no business knowing any type's shape; an objective that names no event is
     * only advanced by an event of the same id as its type, which is the legacy behaviour.
     */
    private static boolean listensFor(ObjectiveInstance objective, VineId eventType) {
        if (objective.type().equals(eventType)) {
            return true;
        }
        if (!(objective.params() instanceof com.google.gson.JsonObject params)) {
            return false;
        }
        com.google.gson.JsonElement declared = params.get("event");
        if (declared == null || !declared.isJsonPrimitive()) {
            return false;
        }
        return declared.getAsString().equals(eventType.toString());
    }

    private int advance(ObjectiveInstance objective, VoxelData payload) {
        ObjectiveType<?> type = objectiveTypes.get(objective.type());
        if (type == null) {
            throw new IllegalStateException("objective type " + objective.type() + " is not registered — a quest"
                + " cannot count an event nothing understands");
        }
        return advanceUnchecked(type, objective, payload);
    }

    @SuppressWarnings("unchecked")
    private static <P> int advanceUnchecked(ObjectiveType<P> type, ObjectiveInstance objective, VoxelData payload) {
        P params = type.paramsCodec().parse(com.mojang.serialization.JsonOps.INSTANCE, objective.params())
            .result().orElseThrow(() -> new IllegalStateException("objective " + objective.id()
                + " parameters did not parse under type " + objective.type()));
        ObjectiveBehavior<P> behavior = type.behavior();
        return behavior.advance(params, payload);
    }

    private static QuestDescriptor descriptor(VineId quest) {
        return VineRegistries.<QuestDescriptor>get(VineQuests.QUEST_TYPE, quest).map(holder -> holder.value())
            .orElse(null);
    }

    /**
     * Every registered quest this player has a state for, in a stable order. Discovery goes
     * through the engine's own id map (registry key {@code "vine:quest <id>"}), because the
     * engine's tree has no key listing and the registry is the authority on what a quest is.
     */
    private List<QuestDescriptor> startedQuests(VoxelData tree) {
        List<QuestDescriptor> out = new ArrayList<>();
        for (QuestDescriptor descriptor : registeredQuests()) {
            if (tree.contains("quests." + descriptor.id() + ".state")) {
                out.add(descriptor);
            }
        }
        return out;
    }

    private static QuestProgress read(VoxelData tree, QuestDescriptor quest) {
        String prefix = "quests." + quest.id();
        if (!tree.contains(prefix + ".state")) {
            List<VineId> objectives = quest.objectives().stream().map(ObjectiveInstance::id).toList();
            return QuestProgress.locked(quest.id(), objectives);
        }
        ProgressState state = ProgressState.valueOf(tree.getString(prefix + ".state"));
        LinkedHashMap<VineId, Integer> counts = new LinkedHashMap<>();
        for (ObjectiveInstance objective : quest.objectives()) {
            counts.put(objective.id(), tree.getInt(prefix + ".obj." + objective.id()));
        }
        LinkedHashSet<Integer> claimed = new LinkedHashSet<>();
        for (int index = 0; index < quest.rewards().size(); index++) {
            if (tree.contains(claimPath(quest.id(), index))) {
                claimed.add(index);
            }
        }
        return new QuestProgress(quest.id(), state, counts, claimed);
    }

    private static void write(VoxelData tree, QuestProgress progress) {
        String prefix = "quests." + progress.quest();
        tree.put(prefix + ".state", progress.state().name());
        for (Map.Entry<VineId, Integer> entry : progress.objectiveCounts().entrySet()) {
            tree.put(prefix + ".obj." + entry.getKey(), entry.getValue());
        }
    }

    private static String claimPath(VineId quest, int index) {
        return "quests." + quest + ".claim." + index;
    }
}
