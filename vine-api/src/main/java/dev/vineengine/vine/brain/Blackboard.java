package dev.vineengine.vine.brain;

import dev.vineengine.vine.data.VoxelData;

/**
 * An actor's memory (sub-08 Stage B): typed values under {@link BlackboardKey}s,
 * stored in a {@code VoxelData} tree so the same tree that a behaviour writes is the
 * one the storage layer persists and the one the TCK can diff.
 *
 * <p>Every node reads and writes through this interface — a behaviour that keeps its
 * own fields instead would not survive a reload and would not be traceable, which is
 * exactly what the golden tick-trace depends on.
 */
public interface Blackboard {

    /** The value for {@code key}, or {@code fallback} when it is absent. */
    <T> T get(BlackboardKey<T> key, T fallback);

    /** The value for {@code key}. */
    <T> T get(BlackboardKey<T> key);

    /** Stores {@code value} under {@code key}. */
    <T> void set(BlackboardKey<T> key, T value);

    /** Whether {@code key} currently holds a value. */
    <T> boolean has(BlackboardKey<T> key);

    /** Removes {@code key} if present. */
    <T> void remove(BlackboardKey<T> key);

    /**
     * The backing tree. Exposed so a consumer can hand it to the storage layer and so
     * the TCK can snapshot it; mutating it behind the key API is allowed but loses the
     * type checking, which is why the typed methods exist.
     */
    VoxelData data();
}
