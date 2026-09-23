package dev.vineengine.vine.internal.net;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import dev.vineengine.vine.net.CodecException;
import dev.vineengine.vine.net.VineBuf;
import dev.vineengine.vine.registry.VineId;

/**
 * The engine-owned {@link VineBuf}: a growable byte-array writer and a
 * bounds-checked reader over a received byte array (sub-05 §2 internals).
 *
 * <p>Every variable-length read validates its length prefix against the
 * remaining byte budget <em>before</em> allocating (§4 — hostile length
 * prefixes throw {@link CodecException}, never large-allocate). Reads are
 * strictly sequential; consuming past the end throws.
 *
 * <p>Stage A allocates one buffer per message; pooled buffers land with the
 * stage-B allocation budget work (§2).
 */
public final class ByteArrayVineBuf implements VineBuf {

    /** UTF-8 encodes at most 4 bytes per char (surrogate pairs). */
    private static final int MAX_UTF_BYTES = MAX_UTF_CHARS * 4;
    private static final int MAX_VAR_INT_BYTES = 5;

    private byte[] data;
    private int writerIndex;
    private int readerIndex;

    private ByteArrayVineBuf(byte[] data, int writerIndex) {
        this.data = data;
        this.writerIndex = writerIndex;
    }

    /** A new empty buffer for encoding. */
    public static ByteArrayVineBuf writable() {
        return new ByteArrayVineBuf(new byte[64], 0);
    }

    /** A reader over received {@code payload} bytes (defensively copied by the caller's contract). */
    public static ByteArrayVineBuf wrap(byte[] payload) {
        return new ByteArrayVineBuf(payload, payload.length);
    }

    /** The encoded bytes, exactly {@code [0, writerIndex)}. */
    byte[] encoded() {
        return Arrays.copyOf(data, writerIndex);
    }

    private int remaining() {
        return writerIndex - readerIndex;
    }

    /** Whether every byte has been consumed — trailing bytes indicate a corrupt frame. */
    boolean fullyRead() {
        return remaining() == 0;
    }

    private void ensureWritable(int count) {
        if (writerIndex + count > data.length) {
            data = Arrays.copyOf(data, Math.max(data.length * 2, writerIndex + count));
        }
    }

    private void writeByte(int value) {
        ensureWritable(1);
        data[writerIndex++] = (byte) value;
    }

    private int readByte() {
        if (remaining() < 1) {
            throw new CodecException("truncated buffer: 1 byte requested, " + remaining() + " remaining");
        }
        return data[readerIndex++] & 0xFF;
    }

    @Override
    public void writeVarInt(int value) {
        int remaining = value;
        while ((remaining & 0xFFFFFF80) != 0) {
            writeByte((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        writeByte(remaining);
    }

    @Override
    public int readVarInt() {
        int value = 0;
        for (int i = 0; i < MAX_VAR_INT_BYTES; i++) {
            int b = readByte();
            value |= (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new CodecException("varInt longer than " + MAX_VAR_INT_BYTES + " bytes");
    }

    @Override
    public void writeLong(long value) {
        ensureWritable(8);
        for (int i = 7; i >= 0; i--) {
            data[writerIndex++] = (byte) (value >>> (i * 8));
        }
    }

    @Override
    public long readLong() {
        if (remaining() < 8) {
            throw new CodecException("truncated long: " + remaining() + " of 8 bytes remaining");
        }
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (data[readerIndex++] & 0xFF);
        }
        return value;
    }

    @Override
    public void writeBoolean(boolean value) {
        writeByte(value ? 1 : 0);
    }

    @Override
    public boolean readBoolean() {
        int b = readByte();
        if (b > 1) {
            throw new CodecException("invalid boolean byte: " + b);
        }
        return b == 1;
    }

    @Override
    public void writeUtf(String value) {
        if (value.length() > MAX_UTF_CHARS) {
            throw new CodecException("string too long: " + value.length() + " chars (max " + MAX_UTF_CHARS + ")");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        ensureWritable(bytes.length);
        System.arraycopy(bytes, 0, data, writerIndex, bytes.length);
        writerIndex += bytes.length;
    }

    @Override
    public String readUtf() {
        int length = readVarInt();
        if (length < 0 || length > MAX_UTF_BYTES) {
            throw new CodecException("utf byte length " + length + " outside [0, " + MAX_UTF_BYTES + "]");
        }
        if (length > remaining()) {
            throw new CodecException("utf length " + length + " exceeds remaining " + remaining());
        }
        String value;
        try {
            value = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, readerIndex, length)).toString();
        } catch (CharacterCodingException e) {
            throw new CodecException("malformed utf-8 payload", e);
        }
        readerIndex += length;
        if (value.length() > MAX_UTF_CHARS) {
            throw new CodecException("decoded string too long: " + value.length() + " chars");
        }
        return value;
    }

    @Override
    public void writeBytes(byte[] value) {
        writeVarInt(value.length);
        ensureWritable(value.length);
        System.arraycopy(value, 0, data, writerIndex, value.length);
        writerIndex += value.length;
    }

    @Override
    public byte[] readBytes() {
        int length = readVarInt();
        if (length < 0 || length > remaining()) {
            throw new CodecException("byte array length " + length + " exceeds remaining " + remaining());
        }
        byte[] value = Arrays.copyOfRange(data, readerIndex, readerIndex + length);
        readerIndex += length;
        return value;
    }

    @Override
    public void writeId(VineId id) {
        writeUtf(id.toString());
    }

    @Override
    public VineId readId() {
        String raw = readUtf();
        try {
            return VineId.parse(raw);
        } catch (IllegalArgumentException e) {
            throw new CodecException("invalid id on wire: \"" + raw + "\"", e);
        }
    }

    @Override
    public byte[] toByteArray() {
        return java.util.Arrays.copyOf(data, writerIndex);
    }

    @Override
    public int readableBytes() {
        return writerIndex - readerIndex;
    }
}
