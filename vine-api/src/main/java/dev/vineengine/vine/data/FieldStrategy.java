package dev.vineengine.vine.data;

import java.util.Objects;

/**
 * Per-field persistence strategy, opt-in (sub-03 §2): where one field of a
 * schema lives. Portable is the default guarantee — identical semantics on
 * every cell; Native trades portability for vanilla/foreign-mod interop.
 *
 * <p>{@link Native} fields are re-read on access and never cached: vanilla
 * machinery (anvil, grindstone) may rewrite them — documented interop
 * semantics, not corruption.
 */
public sealed interface FieldStrategy {

    /** Default: the field rides the engine-owned {@code vine:voxel_data} component. */
    record Portable() implements FieldStrategy {
    }

    /**
     * Opt-in: the field maps to the named native component id (e.g.
     * {@code "minecraft:damage"}) on this cell; the driver absorbs the
     * per-cell mapping differences.
     */
    record Native(String nativeComponentId) implements FieldStrategy {

        public Native {
            Objects.requireNonNull(nativeComponentId, "nativeComponentId");
            if (nativeComponentId.isEmpty()) {
                throw new IllegalArgumentException("nativeComponentId must be non-empty");
            }
        }
    }
}
