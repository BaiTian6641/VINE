package dev.vineengine.vine.net;

/**
 * Bidirectional serializer for one payload type (sub-05 §2). Codecs are pure
 * functions over {@link VineBuf}: no loader or {@code net.minecraft} types, no
 * I/O, no registry lookups.
 *
 * <p><b>Invariants:</b> {@code decode} after {@code encode} returns an equal
 * value and consumes exactly the bytes {@code encode} wrote; {@code decode} on
 * hostile input throws {@link CodecException}, never runs an unbounded
 * allocation or loop (§4 — hostile clients own every byte).
 */
public interface PayloadCodec<T> {

    /**
     * Decodes a value from {@code buf}, consuming exactly the bytes written by
     * {@link #encode}.
     *
     * @throws CodecException on truncated, oversized, or malformed input
     */
    T decode(VineBuf buf);

    /** Encodes {@code value} into {@code buf}. */
    void encode(VineBuf buf, T value);
}
