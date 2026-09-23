package dev.vineengine.vine.capability;

import dev.vineengine.vine.data.VoxelData;

/**
 * Persistence codec for a stateful capability (sub-04 §2): maps the
 * consumer-visible instance to an engine-owned {@link VoxelData} tree and
 * back. The tree inherits every sub-03 guarantee (schema versioning,
 * datafixers, golden fixtures); drivers never hand-write capability NBT.
 *
 * <p><b>Contract:</b> {@code load(save(x))} preserves the portable state of
 * {@code x}; {@code load} on a fresh tree yields the type's zero state (the
 * tree's missing-path semantics give zeros for free).
 *
 * @param <T> the consumer-visible capability interface
 */
public interface CapabilityCodec<T> {

    /** Writes {@code instance}'s portable state into a fresh tree. */
    VoxelData save(T instance);

    /** Reads an instance's portable state back out of {@code data}. */
    T load(VoxelData data);
}
