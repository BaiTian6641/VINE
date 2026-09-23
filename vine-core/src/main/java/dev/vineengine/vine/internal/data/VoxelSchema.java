package dev.vineengine.vine.internal.data;

import java.util.Objects;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.registry.VineId;

/**
 * A named, engine-versioned data schema (sub-03 §2): the identity under which
 * trees register, save, and fix. The engine header carries {@link #id()} and
 * {@link #version()}; that pair is what routes fix-on-load.
 *
 * <p>{@link #codec()} is the consumer's DFU payload codec for authoring and
 * the Stage-C facade surface (the §5.1 DFU carve-out allows
 * {@code com.mojang.serialization} here). Engine versioning never invokes it
 * — fixing is the fixer chain's job, so Mojang data versions can churn
 * without touching consumer schemas.
 */
public record VoxelSchema(VineId id, int version, Codec<VoxelData> codec) {

    public VoxelSchema {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(codec, "codec");
        if (version < 1) {
            throw new IllegalArgumentException("schema version starts at 1, got " + version);
        }
    }
}
