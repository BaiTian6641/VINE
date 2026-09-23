package dev.vineengine.vine.capability;

import dev.vineengine.vine.registry.VineId;

/**
 * A capability kind (sub-04 §2): identity, the consumer-visible interface
 * class, optional persistence, and player-clone semantics.
 *
 * <p>{@code state == null} means the capability is <b>stateless</b>: instances
 * come straight from their provider and never serialize. A non-null codec
 * makes the capability <b>stateful</b>: its instance state persists through
 * {@link CapabilityCodec} into a VoxelData tree on the target's sub-03 attach
 * point — persistence and portability are the same code path (the engine
 * fallback store).
 *
 * @param <T> the consumer-visible capability interface
 */
public record CapabilityType<T>(VineId id, Class<T> apiClass, CapabilityCodec<T> state,
        ClonePolicy clonePolicy) {

    public CapabilityType {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(apiClass, "apiClass");
        java.util.Objects.requireNonNull(clonePolicy, "clonePolicy");
    }

    /** Stateful convenience constructor. */
    public static <T> CapabilityType<T> stateful(VineId id, Class<T> apiClass,
            CapabilityCodec<T> codec, ClonePolicy clonePolicy) {
        return new CapabilityType<>(id, apiClass, codec, clonePolicy);
    }

    /** Stateless convenience constructor — never serialized. */
    public static <T> CapabilityType<T> stateless(VineId id, Class<T> apiClass,
            ClonePolicy clonePolicy) {
        return new CapabilityType<>(id, apiClass, null, clonePolicy);
    }

    /** Whether instances of this type persist through {@link #state()}. */
    public boolean isStateful() {
        return state != null;
    }
}
