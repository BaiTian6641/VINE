package dev.vineengine.vine.net;

import dev.vineengine.vine.registry.VineId;

/**
 * The engine's byte-buffer facade (sub-05 §2) — the only buffer type consumers
 * ever see. No {@code net.minecraft}, loader, or Netty types leak through this
 * surface (Prime Invariant, §5.1); drivers move the opaque bytes the engine
 * encodes into a {@code VineBuf}.
 *
 * <p><b>Invariants:</b> every variable-length read is bounds-checked against the
 * remaining byte budget <em>before</em> any allocation — a hostile length prefix
 * throws {@link CodecException}, it can never trigger a large allocation (§4).
 * {@link #writeUtf} rejects strings over {@value #MAX_UTF_CHARS} chars at encode
 * time, mirroring the vanilla payload limit so an oversized string fails on the
 * sender, not on the wire.
 */
public interface VineBuf {

    /** Maximum string length accepted by {@link #writeUtf}/{@link #readUtf}. */
    int MAX_UTF_CHARS = 32767;

    /** Writes an int in 1–5 bytes (7 bits per byte, continuation-bit form). */
    void writeVarInt(int value);

    /** Reads a varInt; throws {@link CodecException} on truncation or >5 bytes. */
    int readVarInt();

    /** Writes a long as 8 bytes, big-endian. */
    void writeLong(long value);

    /** Reads a big-endian long; throws {@link CodecException} on truncation. */
    long readLong();

    /** Writes one byte: 1 = true, 0 = false. */
    void writeBoolean(boolean value);

    /** Reads a boolean; any byte other than 0/1 throws {@link CodecException}. */
    boolean readBoolean();

    /**
     * Writes a string as varInt byte-length + UTF-8 bytes.
     *
     * @throws CodecException if {@code value} exceeds {@value #MAX_UTF_CHARS} chars
     */
    void writeUtf(String value);

    /**
     * Reads a string written by {@link #writeUtf}; the length prefix is validated
     * against the remaining budget before decoding.
     *
     * @throws CodecException on truncation, an oversized prefix, or a decoded
     *         string over {@value #MAX_UTF_CHARS} chars
     */
    String readUtf();

    /** Writes {@code value} as varInt length + raw bytes. */
    void writeBytes(byte[] value);

    /**
     * Reads a byte array written by {@link #writeBytes}; the length prefix is
     * validated against the remaining budget before the array is allocated.
     *
     * @throws CodecException on a negative or over-budget length prefix
     */
    byte[] readBytes();

    /** Writes an id as its canonical {@code "namespace:path"} string. */
    void writeId(VineId id);

    /** Reads an id written by {@link #writeId}. */
    VineId readId();

    /** Encodes {@code value} with {@code codec} into this buffer. */
    default <T> void write(PayloadCodec<T> codec, T value) {
        codec.encode(this, value);
    }

    /** Decodes a value with {@code codec} from this buffer. */
    default <T> T read(PayloadCodec<T> codec) {
        return codec.decode(this);
    }
}
