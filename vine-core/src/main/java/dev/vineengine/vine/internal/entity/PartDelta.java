package dev.vineengine.vine.internal.entity;

import java.util.List;

import dev.vineengine.vine.entity.PartState;

/**
 * The part-state delta codec (sub-08 Stage D): fixed-point, delta-compressed, and
 * pure — no registry, no actor, no transport. That purity is the point: the headless
 * golden fixture runs this exact code with a classpath of vine-api + vine-core only,
 * so a change in the byte layout fails a fixture instead of silently desyncing a
 * client that never gets tested.
 *
 * <p><b>Layout</b> (one payload per changed actor; the actor rides the envelope):
 * <pre>
 *   u8  version (1)
 *   u8  entry count
 *   varint unsigned server tick
 *   entries: u8 part index (the descriptor's declaration order),
 *            s32 wound in hundredths (fixed point),
 *            u8  flags — bit 0 broken, bit 1 flinched since the previous payload
 * </pre>
 * A part index needs no name on the wire because both sides hold the same structural
 * descriptor; the engine never invents an index, it uses declaration order.
 *
 * <p><b>Deadband.</b> {@link Sent} remembers, per part, the quantized wound and the
 * flinch stamp last put on the wire; {@link #encode} emits only entries that moved.
 * A beast that was hit for less than a hundredth of a point tells nobody, and one
 * that is standing still costs zero bytes.
 */
public final class PartDelta {

    /** The wire format version; a receiver refuses anything else rather than guessing. */
    public static final byte VERSION = 1;

    private static final byte FLAG_BROKEN = 1;
    private static final byte FLAG_FLINCHED = 2;

    private PartDelta() {
    }

    /** What a receiver already knows about one actor's parts. */
    public static final class Sent {

        final int[] wound;
        final long[] flinchTick;

        public Sent(int size) {
            wound = new int[size];
            flinchTick = new long[size];
            java.util.Arrays.fill(wound, Integer.MIN_VALUE);
            java.util.Arrays.fill(flinchTick, Long.MIN_VALUE);
        }
    }

    /**
     * The delta for {@code parts} at {@code serverTick}: only entries whose quantized
     * wound or flinch stamp moved since {@code sent}, or {@code null} when nothing did.
     * Mutates {@code sent} to reflect what has now been told.
     */
    public static byte[] encode(List<PartState> parts, Sent sent, long serverTick) {
        byte[] entries = new byte[parts.size() * 6];
        int count = 0;
        for (int index = 0; index < parts.size(); index++) {
            PartState part = parts.get(index);
            int quantized = quantize(part.wound());
            boolean flinched = index < sent.flinchTick.length && part.hasFlinched()
                && part.lastFlinchTick() != sent.flinchTick[index];
            boolean changed = index >= sent.wound.length || sent.wound[index] != quantized || flinched;
            if (!changed) {
                continue;
            }
            entries[count * 6] = (byte) index;
            writeInt(entries, count * 6 + 1, quantized);
            byte flags = 0;
            if (part.broken()) {
                flags |= FLAG_BROKEN;
            }
            if (flinched) {
                flags |= FLAG_FLINCHED;
            }
            entries[count * 6 + 5] = flags;
            count++;
            if (index < sent.wound.length) {
                sent.wound[index] = quantized;
                sent.flinchTick[index] = part.lastFlinchTick();
            }
        }
        if (count == 0) {
            return null;
        }
        byte[] payload = new byte[2 + 5 + count * 6];
        payload[0] = VERSION;
        payload[1] = (byte) count;
        int cursor = writeVarInt(payload, 2, serverTick);
        System.arraycopy(entries, 0, payload, cursor, count * 6);
        return java.util.Arrays.copyOf(payload, cursor + count * 6);
    }

    /** Applies {@code payload} onto {@code parts} — the receiver's half, and what a test drives. */
    public static List<PartState> apply(List<PartState> parts, byte[] payload) {
        if (payload.length < 3 || payload[0] != VERSION) {
            throw new IllegalArgumentException("part delta: unknown format version "
                + (payload.length == 0 ? "(empty)" : Byte.toString(payload[0])));
        }
        int count = payload[1] & 0xFF;
        int cursor = 2;
        long serverTick = 0L;
        int shift = 0;
        while ((payload[cursor] & 0x80) != 0) {
            serverTick |= (long) (payload[cursor] & 0x7F) << shift;
            shift += 7;
            cursor++;
        }
        serverTick |= (long) (payload[cursor] & 0x7F) << shift;
        cursor++;
        java.util.ArrayList<PartState> out = new java.util.ArrayList<>(parts);
        for (int entry = 0; entry < count; entry++) {
            int index = payload[cursor] & 0xFF;
            if (index >= out.size()) {
                throw new IllegalArgumentException("part delta names part index " + index + " but the descriptor"
                    + " declares " + out.size() + " parts — sender and receiver do not agree on the descriptor");
            }
            int quantized = readInt(payload, cursor + 1);
            byte flags = payload[cursor + 5];
            PartState before = out.get(index);
            long flinchTick = (flags & FLAG_FLINCHED) != 0 ? serverTick : before.lastFlinchTick();
            out.set(index, new PartState(before.name(), quantized / 100.0D, (flags & FLAG_BROKEN) != 0, flinchTick));
            cursor += 6;
        }
        return List.copyOf(out);
    }

    /** The largest payload an actor with {@code partCount} parts can produce. */
    public static int worstCaseBytes(int partCount) {
        // 2 header + 5 worst-case varint tick + 6 per entry.
        return 2 + 5 + partCount * 6;
    }

    /** Fixed point: hundredths of a damage point, finer than any hit zone cares about. */
    public static int quantize(double wound) {
        return (int) Math.round(wound * 100.0D);
    }

    private static void writeInt(byte[] target, int offset, int value) {
        target[offset] = (byte) (value >>> 24);
        target[offset + 1] = (byte) (value >>> 16);
        target[offset + 2] = (byte) (value >>> 8);
        target[offset + 3] = (byte) value;
    }

    private static int readInt(byte[] source, int offset) {
        return ((source[offset] & 0xFF) << 24) | ((source[offset + 1] & 0xFF) << 16)
            | ((source[offset + 2] & 0xFF) << 8) | (source[offset + 3] & 0xFF);
    }

    private static int writeVarInt(byte[] target, int offset, long value) {
        int cursor = offset;
        long remaining = value;
        while ((remaining & ~0x7FL) != 0L) {
            target[cursor++] = (byte) ((remaining & 0x7F) | 0x80);
            remaining >>>= 7;
        }
        target[cursor++] = (byte) remaining;
        return cursor;
    }
}
