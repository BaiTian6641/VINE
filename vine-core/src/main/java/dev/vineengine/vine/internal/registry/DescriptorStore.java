package dev.vineengine.vine.internal.registry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import dev.vineengine.vine.internal.spi.DesignRegistryView;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.registry.DescriptorClass;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.MissingContentPolicy;
import dev.vineengine.vine.registry.VineId;

/**
 * The engine-owned descriptor registry (sub-02 §2): per-type sorted maps, frozen
 * when the engine enters {@code REGISTRIES_FROZEN}. Duplicates and post-freeze
 * writes throw — a conflicting or late registration is a bug, never a guess.
 *
 * <p>Structural types freeze for the JVM session here; design types later ride
 * vanilla dynamic datapack registries (Stage C), but their Java-authored entries
 * are held by the same store so {@code VineRegistries.get} resolves uniformly.
 *
 * <p>Runtime ids are assigned per type in registration order. They become the
 * persistent-map slots of Stage D; until then they are stable within the session
 * post-freeze, not across sessions.
 *
 * <p>Registration is effectively single-threaded (loader init); methods are
 * synchronized so the freeze transition cannot race a write.
 */
public final class DescriptorStore {

    private final Map<VineId, TypeEntries<?>> types = new LinkedHashMap<>();
    private final Map<VineId, DesignEntries> designEntries = new LinkedHashMap<>();
    private final Map<String, MissingContentPolicy> policies = new LinkedHashMap<>();
    /** Structural entries in registration order — the map's deterministic assignment order. */
    private final java.util.List<String> structuralRegistrationOrder = new ArrayList<>();
    private IdMapStore idMap;
    private boolean frozen;

    private Runnable beforeSnapshot;

    /**
     * Installs a run-once action that completes structural authoring before the first
     * snapshot is taken (sub-02 Stage F + sub-08 Stage A).
     *
     * <p>Why here: a cell materializes what a snapshot contains, and on Fabric the
     * native registries freeze long before the engine's own freeze phase — so a
     * descriptor authored in JSON must already be registered by the time any cell asks
     * for the view. The store is the only object that knows both when the view is
     * wanted and when the entries are complete, so the ordering rule lives here rather
     * than in each driver's bootstrap.
     */
    public synchronized void beforeStructuralSnapshot(Runnable action) {
        this.beforeSnapshot = Objects.requireNonNull(action, "action");
    }

    /** Installs the persistent id map backing structural runtime ids (sub-02 Stage D). */
    public void idMap(IdMapStore map) {
        this.idMap = map;
    }

    /**
     * Every structural id-map key, sorted (sub-02 Stage D): after the world map
     * mounts, the engine assigns ids in this order, so a world's numbering never
     * depends on read order, registration order, or the loader cell.
     */
    public synchronized java.util.List<String> structuralKeys() {
        java.util.List<String> sorted = new ArrayList<>(structuralRegistrationOrder);
        java.util.Collections.sort(sorted);
        return List.copyOf(sorted);
    }

    /** Registry ids of every defined structural type (JSON discovery filter). */
    public synchronized java.util.Set<VineId> structuralRegistryIds() {
        java.util.Set<VineId> ids = new java.util.LinkedHashSet<>();
        for (TypeEntries<?> entries : types.values()) {
            if (entries.type.descriptorClass() == DescriptorClass.STRUCTURAL) {
                ids.add(entries.type.registryId());
            }
        }
        return ids;
    }

    /** The defined structural type for {@code registryId}, or null. */
    public synchronized DescriptorType<?> structuralType(VineId registryId) {
        TypeEntries<?> entries = types.get(registryId);
        return entries != null && entries.type.descriptorClass() == DescriptorClass.STRUCTURAL
            ? entries.type
            : null;
    }

    /** Sets a namespace's missing-content policy. */
    public synchronized void setMissingContentPolicy(String namespace, MissingContentPolicy policy) {
        policies.put(Objects.requireNonNull(namespace, "namespace"),
            Objects.requireNonNull(policy, "policy"));
    }

    /** A namespace's policy; {@code KEEP} when unset (the documented default). */
    public synchronized MissingContentPolicy missingContentPolicyFor(String namespace) {
        return policies.getOrDefault(namespace, MissingContentPolicy.KEEP);
    }

    /**
     * Whether an id-map key ({@code registryId entryId}) resolves to content
     * that exists right now: structural registrations and datapack-loaded design
     * entries.
     */
    public synchronized boolean isRegistered(String key) {
        int split = key.indexOf(' ');
        if (split <= 0) {
            return false;
        }
        VineId registryId = VineId.parse(key.substring(0, split));
        VineId entryId = VineId.parse(key.substring(split + 1));
        TypeEntries<?> entries = types.get(registryId);
        if (entries == null) {
            return false;
        }
        if (entries.map.containsKey(entryId)) {
            return true;
        }
        DesignEntries slot = designEntries.get(registryId);
        return slot != null && slot.byId.containsKey(entryId);
    }

    /** Whether {@link #freeze()} has run (engine entered {@code REGISTRIES_FROZEN}). */
    public synchronized boolean frozen() {
        return frozen;
    }

    /**
     * Freezes the store: every subsequent {@link #defineType} and {@link #register}
     * throws. Idempotent; reads stay open.
     */
    public synchronized void freeze() {
        frozen = true;
    }

    public synchronized <D> void defineType(DescriptorType<D> type) {
        Objects.requireNonNull(type, "type");
        VineId registryId = Objects.requireNonNull(type.registryId(),
            () -> "DescriptorType.registryId of " + type.getClass().getName());
        Objects.requireNonNull(type.codec(), () -> "DescriptorType.codec of " + registryId);
        Objects.requireNonNull(type.descriptorClass(), () -> "DescriptorType.descriptorClass of " + registryId);
        if (type.descriptorClass() == DescriptorClass.STRUCTURAL && type.syncToClient()) {
            throw new IllegalArgumentException(
                "structural descriptor type " + registryId + " cannot syncToClient — sync is DESIGN-only (§5.2)");
        }
        checkWritable("define descriptor type " + registryId);
        TypeEntries<?> existing = types.get(registryId);
        if (existing != null) {
            if (existing.type != type) {
                throw new IllegalStateException("descriptor type id " + registryId
                    + " is already defined by " + existing.type.getClass().getName()
                    + "; conflicting definition by " + type.getClass().getName());
            }
            return; // same instance: idempotent
        }
        types.put(registryId, new TypeEntries<>(type));
    }

    public synchronized <D> Holder<D> register(DescriptorType<D> type, VineId id, D data) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(data, "data");
        TypeEntries<D> entries = definedEntries(type);
        checkWritable("register " + id + " in " + type.registryId());
        if (entries.map.containsKey(id)) {
            throw new IllegalStateException(
                "duplicate descriptor id " + id + " in type " + type.registryId());
        }
        // Structural runtime ids are resolved from the persistent per-world map
        // (sub-02 Stage D) at READ time — registration happens before the world
        // (and therefore the map) is mounted, so assigning here would collide with
        // restored ids. The stored holder carries a provisional value; design
        // entries keep load-order ids (they are per-world data, not persisted).
        int runtimeId = entries.type.descriptorClass() == DescriptorClass.STRUCTURAL && idMap != null
            ? -1
            : entries.nextRuntimeId++;
        StoredHolder<D> holder = new StoredHolder<>(id, data, runtimeId);
        entries.map.put(id, holder);
        if (entries.type.descriptorClass() == DescriptorClass.STRUCTURAL) {
            structuralRegistrationOrder.add(entries.type.registryId() + " " + id);
        }
        return holder;
    }

    public synchronized <D> Optional<Holder<D>> get(DescriptorType<D> type, VineId id) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(id, "id");
        TypeEntries<?> entries = types.get(type.registryId());
        if (entries == null) {
            return Optional.empty(); // type never defined: no entries can exist
        }
        if (entries.type != type) {
            throw new IllegalStateException("descriptor type id " + type.registryId()
                + " was defined by a different DescriptorType instance; reuse the exact"
                + " instance passed to defineType");
        }
        @SuppressWarnings("unchecked")
        Holder<D> holder = (Holder<D>) entries.map.get(id);
        if (holder != null) {
            if (entries.type.descriptorClass() == DescriptorClass.STRUCTURAL && idMap != null) {
                // Authoritative id: the map assigns on first read and keeps it
                // stable for the world's lifetime, independent of read order.
                int runtimeId = idMap.assign(entries.type.registryId(), id);
                return Optional.of(new StoredHolder<>(id, holder.value(), runtimeId));
            }
            return Optional.of(holder);
        }
        // DESIGN entries come from the loader's datapack registry (sub-02 Stage C),
        // never from consumer registration — same lookup surface either way.
        if (entries.type.descriptorClass() == DescriptorClass.DESIGN) {
            DesignEntries slot = designEntries.get(type.registryId());
            if (slot != null && slot.byId.containsKey(id)) {
                int runtimeId = 0;
                for (VineId key : slot.byId.keySet()) {
                    if (key.equals(id)) {
                        break;
                    }
                    runtimeId++;
                }
                @SuppressWarnings("unchecked")
                Holder<D> designHolder = (Holder<D>) (Holder<?>) new DesignHolder<>(
                    id, slot.byId.get(id), runtimeId);
                return Optional.of(designHolder);
            }
        }
        return Optional.empty();
    }

    /** Holder for datapack-loaded design entries (runtime id = position in load order). */
    private record DesignHolder<D>(VineId id, Object payload, int runtimeId) implements Holder<D> {

        @SuppressWarnings("unchecked")
        @Override
        public D value() {
            return (D) payload;
        }
    }

    /**
     * Immutable snapshot of the structural slice for driver materialization
     * (sub-02 Stage B): structural types in {@code registryId} order, entries in
     * {@code VineId} order — the same iteration order on every cell. Safe to read
     * after return regardless of later writes or freeze.
     */
    public synchronized StructuralRegistryView structuralView() {
        if (beforeSnapshot != null) {
            // Run once, and only once: the action registers entries, so a second run
            // would re-read the same files for no reason.
            Runnable action = beforeSnapshot;
            beforeSnapshot = null;
            action.run();
        }
        List<StructuralRegistryView.StructuralType> snapshot = new ArrayList<>();
        for (TypeEntries<?> entries : types.values()) {
            if (entries.type.descriptorClass() == DescriptorClass.STRUCTURAL) {
                snapshot.add(new TypeSnapshot(entries.type, List.copyOf(entries.map.values())));
            }
        }
        snapshot.sort(Comparator.comparing(type -> type.type().registryId()));
        return new ViewSnapshot(List.copyOf(snapshot));
    }

    /**
     * Replaces the design entries for {@code registryId} (sub-02 Stage C). Legal
     * after freeze by design: design entries arrive from datapacks at world load
     * and are re-reported on every re-read, never registered by consumers.
     */
    public synchronized void putDesignEntries(VineId registryId, java.util.Map<VineId, Object> entries) {
        DesignEntries slot = designEntries.computeIfAbsent(
            Objects.requireNonNull(registryId, "registryId"), id -> new DesignEntries());
        slot.byId.clear();
        slot.byId.putAll(Objects.requireNonNull(entries, "entries"));
        slot.nextRuntimeId = slot.byId.size();
    }

    /** Snapshot of the current design entries for {@code registryId} (empty when unloaded). */
    public synchronized java.util.Map<VineId, Object> designEntries(VineId registryId) {
        DesignEntries slot = designEntries.get(registryId);
        return slot == null ? java.util.Map.of() : java.util.Map.copyOf(slot.byId);
    }

    /** DESIGN types as the driver needs them (registry id + codec + sync flags). */
    public synchronized DesignRegistryView designView() {
        List<DesignRegistryView.DesignType> snapshot = new ArrayList<>();
        for (TypeEntries<?> entries : types.values()) {
            if (entries.type.descriptorClass() == DescriptorClass.DESIGN) {
                snapshot.add(new DesignSnapshot(entries.type));
            }
        }
        snapshot.sort(Comparator.comparing(DesignRegistryView.DesignType::registryId));
        return () -> List.copyOf(snapshot);
    }

    /** Null-safe lookup for writes: undefined type or foreign instance both fail explicitly. */
    @SuppressWarnings("unchecked")
    private <D> TypeEntries<D> definedEntries(DescriptorType<D> type) {
        Objects.requireNonNull(type, "type");
        VineId registryId = Objects.requireNonNull(type.registryId(),
            () -> "DescriptorType.registryId of " + type.getClass().getName());
        TypeEntries<?> entries = types.get(registryId);
        if (entries == null) {
            throw new IllegalStateException("descriptor type " + registryId
                + " is not defined — call VineRegistries.defineType during engine boot");
        }
        if (entries.type != type) {
            throw new IllegalStateException("descriptor type id " + registryId
                + " was defined by a different DescriptorType instance; reuse the exact"
                + " instance passed to defineType");
        }
        return (TypeEntries<D>) entries;
    }

    private void checkWritable(String action) {
        if (frozen) {
            throw new IllegalStateException(
                "cannot " + action + ": registries are frozen (REGISTRIES_FROZEN entered)");
        }
    }

    /** Design-type entries reported by the driver's datapack registries. */
    private static final class DesignEntries {
        final Map<VineId, Object> byId = new java.util.LinkedHashMap<>();
        int nextRuntimeId;
    }

    private record DesignSnapshot(DescriptorType<?> type) implements DesignRegistryView.DesignType {

        @Override
        public VineId registryId() {
            return type.registryId();
        }

        @Override
        public com.mojang.serialization.Codec<?> codec() {
            return type.codec();
        }

        @Override
        public boolean syncToClient() {
            return type.syncToClient();
        }

        @Override
        public boolean skipWhenEmpty() {
            return type.skipWhenEmpty();
        }
    }

    /** One defined type plus its entries, sorted by id for deterministic iteration. */
    private static final class TypeEntries<D> {
        final DescriptorType<D> type;
        final NavigableMap<VineId, StoredHolder<D>> map = new TreeMap<>();
        int nextRuntimeId;

        TypeEntries(DescriptorType<D> type) {
            this.type = type;
        }
    }

    private record StoredHolder<D>(VineId id, D value, int runtimeId) implements Holder<D> {
    }

    private record ViewSnapshot(List<StructuralType> types) implements StructuralRegistryView {
    }

    private record TypeSnapshot(DescriptorType<?> type, List<? extends Holder<?>> entries)
        implements StructuralRegistryView.StructuralType {
    }
}
