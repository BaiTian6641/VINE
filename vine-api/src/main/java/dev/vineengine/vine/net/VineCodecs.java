package dev.vineengine.vine.net;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.registry.VineId;

/**
 * Ready-made codec primitives over {@link VineBuf} (sub-05 §2). Stage A ships
 * the scalar codecs plus bounded {@link #list} and {@link #optional}
 * combinators; the record-composition builder and the {@code VoxelData} codec
 * land with stage B (sub-03 dependency).
 *
 * <p><b>Invariants:</b> every combinator enforces its bound on read before
 * allocating — {@code list}'s size prefix is checked against both {@code max}
 * and the buffer's remaining budget, so a hostile prefix throws
 * {@link CodecException} instead of allocating (§4).
 */
public final class VineCodecs {

    private VineCodecs() {
    }

    /** VarInt (1–5 byte) int codec. */
    public static final PayloadCodec<Integer> VAR_INT = new PayloadCodec<>() {
        @Override
        public Integer decode(VineBuf buf) {
            return buf.readVarInt();
        }

        @Override
        public void encode(VineBuf buf, Integer value) {
            buf.writeVarInt(value);
        }
    };

    /** Big-endian 8-byte long codec. */
    public static final PayloadCodec<Long> LONG = new PayloadCodec<>() {
        @Override
        public Long decode(VineBuf buf) {
            return buf.readLong();
        }

        @Override
        public void encode(VineBuf buf, Long value) {
            buf.writeLong(value);
        }
    };

    /** Single-byte boolean codec. */
    public static final PayloadCodec<Boolean> BOOL = new PayloadCodec<>() {
        @Override
        public Boolean decode(VineBuf buf) {
            return buf.readBoolean();
        }

        @Override
        public void encode(VineBuf buf, Boolean value) {
            buf.writeBoolean(value);
        }
    };

    /** Length-prefixed UTF-8 string codec, ≤ {@value VineBuf#MAX_UTF_CHARS} chars. */
    public static final PayloadCodec<String> UTF = new PayloadCodec<>() {
        @Override
        public String decode(VineBuf buf) {
            return buf.readUtf();
        }

        @Override
        public void encode(VineBuf buf, String value) {
            buf.writeUtf(value);
        }
    };

    /** Canonical {@code "namespace:path"} id codec. */
    public static final PayloadCodec<VineId> ID = new PayloadCodec<>() {
        @Override
        public VineId decode(VineBuf buf) {
            return buf.readId();
        }

        @Override
        public void encode(VineBuf buf, VineId value) {
            buf.writeId(value);
        }
    };

    /** Length-prefixed raw byte-array codec. */
    public static final PayloadCodec<byte[]> BYTES = new PayloadCodec<>() {
        @Override
        public byte[] decode(VineBuf buf) {
            return buf.readBytes();
        }

        @Override
        public void encode(VineBuf buf, byte[] value) {
            buf.writeBytes(value);
        }
    };

    /**
     * Bounded list codec: varInt size prefix + elements in order.
     *
     * @param max maximum element count; enforced on encode and checked on
     *        decode <em>before</em> the result list is grown
     */
    public static <T> PayloadCodec<List<T>> list(PayloadCodec<T> element, int max) {
        Objects.requireNonNull(element, "element");
        if (max < 0) {
            throw new IllegalArgumentException("max must be >= 0: " + max);
        }
        return new PayloadCodec<>() {
            @Override
            public List<T> decode(VineBuf buf) {
                int size = buf.readVarInt();
                if (size < 0 || size > max) {
                    throw new CodecException("list size " + size + " outside [0, " + max + "]");
                }
                List<T> result = new ArrayList<>(Math.min(size, 1024));
                for (int i = 0; i < size; i++) {
                    result.add(element.decode(buf));
                }
                return List.copyOf(result);
            }

            @Override
            public void encode(VineBuf buf, List<T> value) {
                if (value.size() > max) {
                    throw new CodecException("list size " + value.size() + " exceeds max " + max);
                }
                buf.writeVarInt(value.size());
                for (T item : value) {
                    element.encode(buf, item);
                }
            }
        };
    }

    /** Optional codec: one presence byte + element when present. */
    public static <T> PayloadCodec<Optional<T>> optional(PayloadCodec<T> element) {
        Objects.requireNonNull(element, "element");
        return new PayloadCodec<>() {
            @Override
            public Optional<T> decode(VineBuf buf) {
                return buf.readBoolean() ? Optional.of(element.decode(buf)) : Optional.empty();
            }

            @Override
            public void encode(VineBuf buf, Optional<T> value) {
                buf.writeBoolean(value.isPresent());
                value.ifPresent(item -> element.encode(buf, item));
            }
        };
    }
}
