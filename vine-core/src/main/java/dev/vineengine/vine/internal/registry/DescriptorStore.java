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

import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.registry.DescriptorClass;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.Holder;
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
    private boolean frozen;

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
        StoredHolder<D> holder = new StoredHolder<>(id, data, entries.nextRuntimeId++);
        entries.map.put(id, holder);
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
        return Optional.ofNullable(holder);
    }

    /**
     * Immutable snapshot of the structural slice for driver materialization
     * (sub-02 Stage B): structural types in {@code registryId} order, entries in
     * {@code VineId} order — the same iteration order on every cell. Safe to read
     * after return regardless of later writes or freeze.
     */
    public synchronized StructuralRegistryView structuralView() {
        List<StructuralRegistryView.StructuralType> snapshot = new ArrayList<>();
        for (TypeEntries<?> entries : types.values()) {
            if (entries.type.descriptorClass() == DescriptorClass.STRUCTURAL) {
                snapshot.add(new TypeSnapshot(entries.type, List.copyOf(entries.map.values())));
            }
        }
        snapshot.sort(Comparator.comparing(type -> type.type().registryId()));
        return new ViewSnapshot(List.copyOf(snapshot));
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
