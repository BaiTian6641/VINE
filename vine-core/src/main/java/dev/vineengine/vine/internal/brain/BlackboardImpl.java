package dev.vineengine.vine.internal.brain;

import java.util.Objects;

import dev.vineengine.vine.brain.Blackboard;
import dev.vineengine.vine.brain.BlackboardKey;
import dev.vineengine.vine.data.VoxelData;

/**
 * The engine's blackboard (sub-08 Stage B): typed access over a {@code VoxelData}
 * tree, which is the same tree the storage layer persists. Contract enforcement is
 * the key's job ({@link BlackboardKey} owns the encoding); this class only routes.
 */
public final class BlackboardImpl implements Blackboard {

    private final VoxelData data;

    public BlackboardImpl(VoxelData data) {
        this.data = Objects.requireNonNull(data, "data");
    }

    @Override
    public <T> T get(BlackboardKey<T> key, T fallback) {
        T value = key.read(data);
        return value == null ? fallback : value;
    }

    @Override
    public <T> T get(BlackboardKey<T> key) {
        return key.read(data);
    }

    @Override
    public <T> void set(BlackboardKey<T> key, T value) {
        key.write(data, value);
    }

    @Override
    public <T> boolean has(BlackboardKey<T> key) {
        return data.contains(key.path());
    }

    @Override
    public <T> void remove(BlackboardKey<T> key) {
        data.remove(key.path());
    }

    @Override
    public VoxelData data() {
        return data;
    }
}
