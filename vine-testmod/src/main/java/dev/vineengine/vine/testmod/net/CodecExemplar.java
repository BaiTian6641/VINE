package dev.vineengine.vine.testmod.net;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.net.CodecException;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.VineCodecs;
import dev.vineengine.vine.registry.VineId;

/**
 * Codec-DSL exemplar (sub-05 Stage B, sub-22 content contract): one nested
 * record codec — scalar, bounded list, optional, and a {@code VoxelData} field —
 * round-tripped through the engine's buffers, plus the hostile-input cases the
 * invariants promise (oversized list prefix, truncation, bogus voxel blob) —
 * each rejected with a {@code CodecException}, never a large allocation.
 */
public final class CodecExemplar {

    /** The nested sample: every Stage B combinator in one payload. */
    public record Sample(int level, List<String> tags, Optional<VineId> focus, VoxelData extra) {
    }

    private static final VineId SCHEMA = VineId.of("vine_test", "codec_sample");

    public static final PayloadCodec<Sample> CODEC = VineCodecs.<Sample>record()
        .field("level", VineCodecs.VAR_INT, Sample::level)
        .field("tags", VineCodecs.list(VineCodecs.UTF, 8), Sample::tags)
        .field("focus", VineCodecs.optional(VineCodecs.ID), Sample::focus)
        .field("extra", VineCodecs.VOXEL, Sample::extra)
        .build(CodecExemplar::factory);

    private CodecExemplar() {
    }

    /** Field values arrive in declaration order (the builder's contract). */
    @SuppressWarnings("unchecked")
    private static Sample factory(Object[] values) {
        return new Sample((Integer) values[0], (List<String>) values[1],
            (Optional<VineId>) values[2], (VoxelData) values[3]);
    }

    /** Registers the sample schema; call from consumer init (before the freeze). */
    public static void registerSchema() {
        VineData.registerSchema(new VoxelSchema(SCHEMA, 1, Codec.unit(null)), List.of());
    }

    /** Command entry: round-trip plus hostile cases, one printed line per check. */
    public static void runProof() {
        VoxelData extra = VineData.create(SCHEMA);
        extra.put("mana", 42);
        extra.put("stats.kills", 3);
        Sample sample = new Sample(7, List.of("alpha", "beta"),
            Optional.of(VineId.of("vine_test", "hunt")), extra);

        byte[] encoded = VineCodecs.encode(CODEC, sample);
        System.out.println("vine-testmod: codec encoded bytes=" + encoded.length);
        System.out.println("vine-testmod: codec hex=" + hex(encoded));

        Sample decoded = VineCodecs.decode(CODEC, encoded);
        System.out.println("vine-testmod: codec decoded level=" + decoded.level()
            + " tags=" + decoded.tags()
            + " focus=" + decoded.focus().map(VineId::toString).orElse("none")
            + " mana=" + decoded.extra().getInt("mana")
            + " kills=" + decoded.extra().getInt("stats.kills"));

        System.out.println("vine-testmod: codec list rejected="
            + rejects(() -> VineCodecs.decode(VineCodecs.list(VineCodecs.UTF, 8), oversizedListBytes())));
        System.out.println("vine-testmod: codec truncated rejected="
            + rejects(() -> VineCodecs.decode(CODEC, java.util.Arrays.copyOf(encoded, encoded.length - 3))));
        System.out.println("vine-testmod: codec voxel rejected="
            + rejects(() -> VineCodecs.decode(VineCodecs.VOXEL, oversizedVoxelBytes())));
        System.out.println("vine-testmod: codec trailing rejected="
            + rejects(() -> VineCodecs.decode(VineCodecs.VAR_INT,
                new byte[] { 1, 0 })));
    }

    /** A list payload whose size prefix (9) exceeds the declared max (8). */
    private static byte[] oversizedListBytes() {
        dev.vineengine.vine.net.VineBuf buf = VineCodecs.buffer();
        buf.writeVarInt(9);
        return buf.toByteArray();
    }

    /** A voxel payload whose length prefix is far beyond the remaining budget. */
    private static byte[] oversizedVoxelBytes() {
        dev.vineengine.vine.net.VineBuf buf = VineCodecs.buffer();
        buf.writeVarInt(Integer.MAX_VALUE);
        return buf.toByteArray();
    }

    private static boolean rejects(Runnable decode) {
        try {
            decode.run();
            return false;
        } catch (CodecException expected) {
            return true;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) {
            out.append(String.format("%02x", b));
        }
        return out.toString();
    }
}
