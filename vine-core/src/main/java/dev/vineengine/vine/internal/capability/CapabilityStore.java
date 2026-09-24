package dev.vineengine.vine.internal.capability;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.capability.CapabilityProvider;
import dev.vineengine.vine.capability.CapabilityScope;
import dev.vineengine.vine.capability.CapabilityTarget;
import dev.vineengine.vine.capability.CapabilityType;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.internal.CapabilityBackend;
import dev.vineengine.vine.registry.VineId;

/**
 * vine-core's capability machinery (sub-04 §2 internals): provider bindings
 * per scope, per-target instance cache, and the VoxelData-backed fallback
 * store — the portability floor where persistence and portability are one
 * code path.
 *
 * <p><b>Target identity:</b> instances and state cache keyed by target object
 * identity ({@link IdentityHashMap}) — a capability belongs to the exact
 * attach point it was attached to, never to an equal-looking one.
 *
 * <p><b>Stateful types:</b> the instance returned by {@code find} is produced
 * by the type's codec from an engine-owned tree at schema
 * {@code vine:cap/<namespace>/<path>} — the consumer implementation reads and
 * writes that state; mutations persist at the target's save boundary (sub-03
 * Stage D/E) without any codec re-invocation. <b>Stateless types</b> are pure
 * provider instances and never serialize.
 *
 * <p><b>Foreign queries</b> degrade to empty until a cell driver binds a
 * {@code CapabilityDriver} (sub-04 Stage C) — absence is a value, never an
 * exception (layering rule).
 */
public final class CapabilityStore implements CapabilityBackend {

    private static final System.Logger LOG = System.getLogger("vine.capability");

    private final CapabilityRegistry registry = new CapabilityRegistry();
    private final Map<CapabilityScope, Map<VineId, CapabilityProvider<?>>> providers =
            new HashMap<>();
    private final IdentityHashMap<CapabilityTarget, Map<VineId, Object>> instances =
            new IdentityHashMap<>();
    private final IdentityHashMap<CapabilityTarget, Map<VineId, VoxelData>> trees =
            new IdentityHashMap<>();

    public void freeze() {
        registry.freeze();
    }

    /** The engine-owned schema id backing a stateful type's instances. */
    public static VineId schemaIdFor(CapabilityType<?> type) {
        return schemaIdFor(type.id());
    }

    /**
     * Schema id derived from a capability type id (flushes and trees key by id) —
     * public because the driver-side native query needs it to pick a capability's
     * slice out of a holder's payload bundle.
     */
    public static VineId schemaIdFor(VineId typeId) {
        return VineId.of("vine", "cap/" + typeId.namespace() + "/" + typeId.path());
    }

    // ------------------------------------------------------------------
    // CapabilityBackend
    // ------------------------------------------------------------------

    @Override
    public synchronized <T> CapabilityType<T> register(CapabilityType<T> type) {
        registry.register(type);
        if (type.isStateful()) {
            // Identity + version only: blobs route through the engine's wire
            // codec (sub-03 §2), never through this placeholder codec.
            VineData.registerSchema(
                new VoxelSchema(schemaIdFor(type), 1, Codec.unit(null)), List.of());
        }
        return type;
    }

    @Override
    public synchronized <T> void attach(CapabilityType<T> type, CapabilityScope scope,
            CapabilityProvider<T> provider) {
        if (registry.frozen()) {
            throw new IllegalStateException("cannot attach provider for " + type.id()
                + " — capability registration closed at REGISTRIES_FROZEN");
        }
        providers.computeIfAbsent(scope, s -> new HashMap<>()).put(type.id(), provider);
        // Attaching is the engine's statement "this type serves this scope", so a
        // bound cell driver also exposes it natively (sub-04 Stage C/D). Exposure
        // failures are the driver's to report; they never fail the attach.
        dev.vineengine.vine.internal.spi.CapabilityDriver driver = CapabilityDriverBinding.bound();
        if (driver != null) {
            try {
                driver.exposeNative(scope, type.id(), type.apiClass());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] caps: native exposure of " + type.id() + " failed (continuing): " + e);
            }
        }
    }

    @Override
    public synchronized <T> Optional<T> find(CapabilityType<T> type, CapabilityTarget target) {
        Map<VineId, CapabilityProvider<?>> scopeProviders = providers.get(target.scope());
        if (scopeProviders == null || !scopeProviders.containsKey(type.id())) {
            return Optional.empty();
        }
        @SuppressWarnings("unchecked")
        T instance = (T) instances
            .computeIfAbsent(target, t -> new HashMap<>())
            .computeIfAbsent(type.id(), id -> createInstance(type, target, scopeProviders));
        return Optional.ofNullable(instance);
    }

    private <T> Object createInstance(CapabilityType<T> type, CapabilityTarget target,
            Map<VineId, CapabilityProvider<?>> scopeProviders) {
        if (type.isStateful()) {
            VoxelData tree = trees
                .computeIfAbsent(target, t -> new HashMap<>())
                .computeIfAbsent(type.id(), id -> treeFor(target, schemaIdFor(type)));
            return type.state().load(tree);
        }
        @SuppressWarnings("unchecked")
        CapabilityProvider<T> provider = (CapabilityProvider<T>) scopeProviders.get(type.id());
        return provider.create(target);
    }

    @Override
    public synchronized <T> Optional<T> findForeign(VineId nativeId, Class<T> apiClass,
            CapabilityTarget target) {
        // Sub-04 Stage C/D: a bound driver answers from the target's native
        // carrier; the result is wrapped at this boundary, and a miss (or an
        // untyped answer) is absence — never an exception.
        dev.vineengine.vine.internal.spi.CapabilityDriver driver = CapabilityDriverBinding.bound();
        if (driver == null) {
            return Optional.empty();
        }
        Object nativeResult;
        try {
            nativeResult = driver.queryNative(nativeId, target);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return apiClass.isInstance(nativeResult) ? Optional.of(apiClass.cast(nativeResult)) : Optional.empty();
    }

    /** Flushes every live capability tree for {@code target} through the storage layer. */
    public synchronized void flush(CapabilityTarget target) {
        Map<VineId, VoxelData> byType = trees.get(target);
        if (byType == null) {
            return;
        }
        dev.vineengine.vine.internal.spi.VoxelStorageDriver storage =
            dev.vineengine.vine.internal.data.VoxelStorageBinding.bound();
        if (storage == null) {
            return;
        }
        dev.vineengine.vine.data.VoxelTarget voxelTarget = voxelTargetOf(target);
        for (Map.Entry<VineId, VoxelData> entry : byType.entrySet()) {
            VineId schemaId = schemaIdFor(entry.getKey());
            try {
                // The tree's own dirty record (sub-03 Stage E), never a wildcard:
                // a capability that was read but not changed writes nothing.
                var engine = dev.vineengine.vine.internal.data.VoxelStorageBinding.engine();
                Set<String> dirty = engine == null ? Set.of("*") : engine.drainDirty(entry.getValue());
                storage.flushDirty(voxelTarget, schemaId, dirty);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] capability flush failed for " + entry.getKey() + " (continuing): " + e);
            }
        }
    }

    private static VoxelData treeFor(CapabilityTarget target, VineId schemaId) {
        try {
            // Prefer the target's attach point: capability state then rides the
            // same carrier as every other engine tree (sub-03), on every cell.
            return VineData.of(voxelTargetOf(target), schemaId);
        } catch (RuntimeException unsupported) {
            // Scopes without an attach point yet (BE/entity/player) keep in-memory
            // trees until their drivers land with the Mixin wave.
            return VineData.create(schemaId);
        }
    }

    /** Bridges a capability target onto the storage layer's target vocabulary. */
    private static dev.vineengine.vine.data.VoxelTarget voxelTargetOf(CapabilityTarget target) {
        return switch (target) {
            case CapabilityTarget.ItemCapabilityTarget item ->
                new dev.vineengine.vine.data.ItemStackTarget(item.raw());
            case CapabilityTarget.BlockCapabilityTarget block ->
                new dev.vineengine.vine.data.BlockEntityTarget(block.raw());
            case CapabilityTarget.EntityCapabilityTarget entity ->
                new dev.vineengine.vine.data.EntityTarget(entity.raw());
            case CapabilityTarget.PlayerCapabilityTarget player ->
                new dev.vineengine.vine.data.PlayerTarget(player.player());
        };
    }

    @Override
    public synchronized void invalidate(CapabilityTarget target) {
        instances.remove(target);
        trees.remove(target);
    }

    @Override
    public synchronized <T> void applyClone(CapabilityType<T> type, CapabilityTarget oldTarget,
            CapabilityTarget newTarget) {
        switch (type.clonePolicy()) {
            case NONE -> {
                // New target starts fresh on first find — nothing to do.
            }
            case FULL -> {
                Map<VineId, VoxelData> oldTrees = trees.get(oldTarget);
                if (oldTrees != null && oldTrees.get(type.id()) != null) {
                    trees.computeIfAbsent(newTarget, t -> new HashMap<>())
                            .put(type.id(), oldTrees.get(type.id()).copy());
                }
            }
            case CUSTOM -> {
                Map<VineId, CapabilityProvider<?>> scopeProviders = providers.get(newTarget.scope());
                if (scopeProviders != null && scopeProviders.containsKey(type.id())) {
                    instances.computeIfAbsent(newTarget, t -> new HashMap<>())
                            .computeIfAbsent(type.id(),
                                id -> createInstance(type, newTarget, scopeProviders));
                }
            }
        }
    }

    /**
     * The target's current tree for {@code type}, if one exists — the handle a
     * driver's save boundary persists through sub-03 storage (Stage D/E).
     */
    synchronized Optional<VoxelData> tree(CapabilityType<?> type, CapabilityTarget target) {
        Map<VineId, VoxelData> byType = trees.get(target);
        return byType == null ? Optional.empty() : Optional.ofNullable(byType.get(type.id()));
    }
}
