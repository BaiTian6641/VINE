package dev.vineengine.vine.brain;

import java.util.Objects;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

/**
 * A typed key into a brain's {@link Blackboard} (sub-08 Stage B). Keys are declared
 * once (statically or by a consumer) and carry their value type, so a blackboard
 * read cannot return a value of the wrong shape and a design-authored key can be
 * checked at registration rather than at the moment an entity happens to walk into
 * it.
 *
 * <p>The key's path is also its {@code VoxelData} path, which is what makes the
 * blackboard persistable: the tree a brain writes through these keys is the same
 * tree the storage layer saves (sub-03), so a reloaded brain resumes with its
 * memory intact.
 *
 * <p><b>Invariants:</b> the path is a valid {@code VoxelData} path; the type is one
 * of the supported value kinds ({@code Integer}, {@code Long}, {@code Double},
 * {@code String}, {@code Boolean} — booleans encode as 0/1 because the data layer has
 * no boolean kind). Immutable; equality is identity-of-fields.
 *
 * @param path the blackboard path, also the storage path
 * @param type the value type
 * @param <T>  the value type
 */
public record BlackboardKey<T>(String path, Class<T> type) {

    /** An {@code int} key. */
    public static BlackboardKey<Integer> intKey(String path) {
        return new BlackboardKey<>(path, Integer.class);
    }

    /** A {@code long} key (tick stamps, counters that outgrow int). */
    public static BlackboardKey<Long> longKey(String path) {
        return new BlackboardKey<>(path, Long.class);
    }

    /** A {@code double} key. */
    public static BlackboardKey<Double> doubleKey(String path) {
        return new BlackboardKey<>(path, Double.class);
    }

    /** A {@code boolean} key (stored as 0/1 in the data layer). */
    public static BlackboardKey<Boolean> boolKey(String path) {
        return new BlackboardKey<>(path, Boolean.class);
    }

    /** A string key (ids, phase names, free-form state). */
    public static BlackboardKey<String> stringKey(String path) {
        return new BlackboardKey<>(path, String.class);
    }

    /** A key holding an engine id (the common case for "who am I chasing"). */
    public static BlackboardKey<VineId> idKey(String path) {
        return new BlackboardKey<>(path, VineId.class);
    }

    public BlackboardKey {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        if (path.isBlank() || path.startsWith(".") || path.endsWith(".") || path.contains("..")) {
            throw new IllegalArgumentException("BlackboardKey.path must be a valid VoxelData path: " + path);
        }
        if (type != Integer.class && type != Long.class && type != Double.class && type != String.class
                && type != Boolean.class && type != VineId.class) {
            throw new IllegalArgumentException("BlackboardKey " + path + ": unsupported value type " + type.getName()
                + " (Integer, Long, Double, String, Boolean or VineId)");
        }
    }

    /**
     * Reads this key's value out of {@code data}, or {@code null} when it is absent.
     *
     * <p>Public because the engine's blackboard implementation lives in vine-core and
     * must go through the key to keep its type contract; a consumer never calls this
     * (it calls {@code Blackboard.get}).
     */
    @SuppressWarnings("unchecked")
    public T read(VoxelData data) {
        if (!data.contains(path)) {
            return null;
        }
        if (type == Integer.class) {
            return (T) Integer.valueOf(data.getInt(path));
        }
        if (type == Long.class) {
            return (T) Long.valueOf(data.getLong(path));
        }
        if (type == Double.class) {
            return (T) Double.valueOf(data.getDouble(path));
        }
        if (type == String.class) {
            return (T) data.getString(path);
        }
        if (type == Boolean.class) {
            return (T) Boolean.valueOf(data.getInt(path) != 0);
        }
        return (T) VineId.parse(data.getString(path));
    }

    /** Writes {@code value} for this key into {@code data} (engine-internal, see {@link #read}). */
    public void write(VoxelData data, T value) {
        Objects.requireNonNull(value, "value");
        if (type == Integer.class) {
            data.put(path, ((Integer) value).intValue());
        } else if (type == Long.class) {
            data.put(path, ((Long) value).longValue());
        } else if (type == Double.class) {
            data.put(path, ((Double) value).doubleValue());
        } else if (type == String.class) {
            data.put(path, (String) value);
        } else if (type == Boolean.class) {
            data.put(path, ((Boolean) value).booleanValue() ? 1 : 0);
        } else {
            data.put(path, ((VineId) value).toString());
        }
    }
}
