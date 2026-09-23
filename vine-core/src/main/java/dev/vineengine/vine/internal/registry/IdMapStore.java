package dev.vineengine.vine.internal.registry;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.internal.spi.WorldStoreSpi;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.MissingContentPolicy;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

import com.mojang.serialization.Codec;

/**
 * The engine-owned persistent {@code VineId}<->int map (sub-02 Stage D): stable
 * ints per structural descriptor, stored in the world so every cell — Fabric
 * included — agrees without loader registry-persistence machinery.
 *
 * <p>Assignment is append-only: an id keeps its int for the life of the world;
 * new ids take the next free int. On world load, entries whose {@code VineId} is
 * no longer registered are handled by the owning namespace's
 * {@link MissingContentPolicy} — {@code KEEP} (default) retains the mapping so
 * re-adding the content restores the original int, {@code DROP} releases it, and
 * {@code FAIL} refuses the load. Every decision is logged with counts.
 */
public final class IdMapStore {

    private static final System.Logger LOG = System.getLogger("vine.registry");

    private static final VineId SCHEMA = VineId.of("vine", "id_map");
    private static final String STORE_KEY = "id_map";
    private static boolean schemaRegistered;

    /** Registry id + entry id -> stable int. */
    private final Map<String, Integer> byKey = new LinkedHashMap<>();
    private final Map<Integer, String> byInt = new LinkedHashMap<>();
    private volatile WorldStoreSpi store;
    private int nextInt;

    /** Registers the map's schema; must run before the schema registry freezes. */
    public static synchronized void ensureSchema() {
        if (schemaRegistered) {
            return;
        }
        VineData.registerSchema(new VoxelSchema(SCHEMA, 1, Codec.unit(null)), java.util.List.of());
        schemaRegistered = true;
    }

    /** Stable int for {@code entryId} in {@code registryId} — assigned once, never reused. */
    public synchronized int assign(VineId registryId, VineId entryId) {
        String key = key(registryId, entryId);
        Integer existing = byKey.get(key);
        if (existing != null) {
            return existing;
        }
        while (byInt.containsKey(nextInt)) {
            nextInt++;
        }
        int assigned = nextInt++;
        byKey.put(key, assigned);
        byInt.put(assigned, key);
        return assigned;
    }

    /**
     * Assigns ids to every key that has none yet, in {@code keys} order — called
     * right after the world map mounts, so the map is complete and a world's
     * numbering is deterministic rather than read-order dependent.
     *
     * @return the number of keys that received a fresh id
     */
    public synchronized int assignMissing(java.util.List<String> keys) {
        int assigned = 0;
        for (String key : keys) {
            if (byKey.containsKey(key)) {
                continue;
            }
            while (byInt.containsKey(nextInt)) {
                nextInt++;
            }
            byInt.put(nextInt, key);
            byKey.put(key, nextInt);
            nextInt++;
            assigned++;
        }
        return assigned;
    }

    /** The id currently owning {@code runtimeId}, when any. */
    public synchronized java.util.Optional<String> keyOf(int runtimeId) {
        return java.util.Optional.ofNullable(byInt.get(runtimeId));
    }

    /** Snapshot of every live mapping ({@code registryId entryId} -> int), sorted. */
    public synchronized java.util.Map<String, Integer> mappings() {
        java.util.TreeMap<String, Integer> sorted = new java.util.TreeMap<>(byKey);
        return java.util.Collections.unmodifiableMap(sorted);
    }

    /** Live mapping count — the TCK's observability hook. */
    public synchronized int size() {
        return byKey.size();
    }

    public synchronized byte[] snapshot() {
        ensureSchema();
        VoxelData root = VineData.create(SCHEMA);
        root.put("count", byKey.size());
        int index = 0;
        for (Map.Entry<String, Integer> entry : byKey.entrySet()) {
            root.put("entries." + index + ".key", entry.getKey());
            root.put("entries." + index + ".runtimeId", entry.getValue());
            index++;
        }
        root.put("nextInt", nextInt);
        return VineData.encode(root);
    }

    /**
     * Restores the map and applies the missing-content policy to mappings whose
     * content is no longer registered ({@code present} decides that).
     *
     * @return the number of live mappings after the policy pass (kept/dropped logged)
     */
    public synchronized int restore(byte[] blob, java.util.function.Predicate<String> present) {
        if (blob == null || blob.length == 0) {
            return 0;
        }
        VoxelData root = VineData.decode(blob);
        // The stored blob is authoritative: drop every pre-mount assignment
        // (registration reads may have resolved ids against an empty map).
        byKey.clear();
        byInt.clear();
        int count = root.getInt("count");
        int kept = 0;
        int dropped = 0;
        int failed = 0;
        for (int index = 0; index < count; index++) {
            String prefix = "entries." + index;
            if (!root.contains(prefix + ".key")) {
                continue;
            }
            String key = root.getString(prefix + ".key");
            int runtimeId = root.getInt(prefix + ".runtimeId");
            if (present.test(key)) {
                byKey.put(key, runtimeId);
                byInt.put(runtimeId, key);
                kept++;
                continue;
            }
            MissingContentPolicy policy = VineRegistries.missingContentPolicyFor(namespaceOf(key));
            switch (policy) {
                case KEEP -> {
                    // Placeholder retention: the mapping survives, so re-adding
                    // the content restores the original int.
                    byKey.put(key, runtimeId);
                    byInt.put(runtimeId, key);
                    kept++;
                    LOG.log(System.Logger.Level.INFO,
                        "[VINE] id map: keeping mapping for absent content " + key + " (policy KEEP)");
                }
                case DROP -> {
                    dropped++;
                    LOG.log(System.Logger.Level.INFO,
                        "[VINE] id map: dropping mapping for absent content " + key + " (policy DROP)");
                }
                case FAIL -> failed++;
                default -> throw new IllegalStateException("unreachable policy " + policy);
            }
        }
        if (failed > 0) {
            throw new IllegalStateException(
                "[VINE] id map: " + failed + " entrie(s) reference content that is no longer registered,"
                    + " and this namespace's policy is FAIL — refusing to load the world");
        }
        nextInt = Math.max(root.getInt("nextInt"), byInt.isEmpty() ? 0
            : byInt.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1);
        LOG.log(System.Logger.Level.INFO,
            "[VINE] id map restored: " + kept + " kept, " + dropped + " dropped, next=" + nextInt);
        return byKey.size();
    }

    /** Mounts the driver's world store and restores the map (world load). */
    public void mount(WorldStoreSpi spi) {
        this.store = java.util.Objects.requireNonNull(spi, "spi");
        restore(spi.load(STORE_KEY), key -> VineRegistries.isRegistered(key));
    }

    /** Saves the map through the mounted store; a no-op without one. */
    public void flush() {
        WorldStoreSpi current = store;
        if (current == null) {
            return;
        }
        try {
            synchronized (this) {
                current.save(STORE_KEY, snapshot());
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "[VINE] id map flush failed (world save continues): " + e);
        }
    }

    private static String key(VineId registryId, VineId entryId) {
        return registryId + " " + entryId;
    }

    /** The entry namespace of a map key ({@code registryId entryId}). */
    private static String namespaceOf(String key) {
        int split = key.indexOf(' ');
        String entry = split < 0 ? key : key.substring(split + 1);
        int colon = entry.indexOf(':');
        return colon < 0 ? entry : entry.substring(0, colon);
    }
}
