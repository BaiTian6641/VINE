package dev.vineengine.vine.internal.entity;

import java.util.List;
import java.util.Map;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntityRef;

/**
 * Part-state sync (sub-08 Stage D): the engine tells clients which parts changed, in
 * fixed-point, delta-compressed form, and never re-sends a value that has not moved.
 *
 * <p><b>Why not just send the parts every tick.</b> The plan's budget is ≤ 2.5 KB/s per
 * multipart entity at twelve parts; a naive per-tick full update is 74 bytes × 20 = 1.5
 * KB/s *before* any fight happens, and a herd of beasts would spend the server's
 * bandwidth on bodies that are standing still. Here a hit costs about seven bytes, a
 * standing beast costs nothing at all, and the number is measurable
 * ({@link #bytesSent()}) rather than asserted in a comment.
 *
 * <p><b>What a delta does not carry.</b> Geometry: a cell's own bodies are already
 * synced by the game for the actors the clients can see. Playback: which clip is
 * playing is sub-09's business, not part state. So this payload holds exactly the
 * engine's authoritative part state — wound totals, broken flags, flinch stamps — which
 * is the part a client cannot derive and must be told.
 *
 * <p>The byte layout lives in {@link PartDelta}, which is pure and therefore carries the
 * headless golden fixture; this class is the plumbing around it: who is dirty, what has
 * been said, how many bytes it cost, and where the transport is.
 */
public final class PartSync {

    /** Installed by the engine's transport; the default accepts and discards (headless boot). */
    public interface Sender {
        void send(VineEntityRef ref, byte[] payload);
    }

    private static volatile Sender SENDER = (ref, payload) -> {
        // No transport bound (headless boot, or a cell that has not bound one): the
        // delta is still built and counted, so budget measurements work everywhere.
    };

    private static final Set<VineEntityRef> DIRTY = ConcurrentHashMap.newKeySet();
    private static final Map<VineEntityRef, PartDelta.Sent> LAST_SENT = new ConcurrentHashMap<>();
    private static final AtomicLong BYTES = new AtomicLong();
    private static final AtomicLong PAYLOADS = new AtomicLong();
    private static final AtomicLong LAST_PAYLOAD_BYTES = new AtomicLong();

    private PartSync() {
    }

    /** Installs the transport; {@code null} restores the discarding default. */
    public static void sender(Sender sender) {
        SENDER = sender == null ? (ref, payload) -> {
        } : sender;
    }

    /** Marks {@code ref} as having changed state that a client needs. */
    static void markDirty(VineEntityRef ref) {
        DIRTY.add(ref);
    }

    /**
     * Sends {@code ref}'s pending delta, if it has one. Called from the actor's own
     * tick, so a hit that lands earlier in the same server tick is on the wire before
     * that tick ends — one tick at most, never drift.
     */
    static void flush(VineEntityRef ref, long serverTick) {
        if (!DIRTY.remove(ref)) {
            return;
        }
        List<PartState> parts = PartRuntime.parts(ref);
        PartDelta.Sent sent = LAST_SENT.computeIfAbsent(ref, key -> new PartDelta.Sent(parts.size()));
        byte[] payload = PartDelta.encode(parts, sent, serverTick);
        if (payload == null) {
            return;
        }
        LAST_PAYLOAD_BYTES.set(payload.length);
        BYTES.addAndGet(payload.length);
        PAYLOADS.incrementAndGet();
        SENDER.send(ref, payload);
    }

    /** Forgets everything known about {@code ref} — an actor that is gone needs no deltas. */
    static void forget(VineEntityRef ref) {
        DIRTY.remove(ref);
        LAST_SENT.remove(ref);
    }

    /** An amount in the wire's fixed point (hundredths): the value every cell shares. */
    static double quantize(double amount) {
        return PartDelta.quantize(amount) / 100.0D;
    }

    /** Bytes sent since the last {@link #resetCounters()} — the budget measurement. */
    public static long bytesSent() {
        return BYTES.get();
    }

    /** Payloads sent since the last {@link #resetCounters()}. */
    public static long payloadsSent() {
        return PAYLOADS.get();
    }

    /** The size of the most recent payload. */
    public static long lastPayloadBytes() {
        return LAST_PAYLOAD_BYTES.get();
    }

    public static void resetCounters() {
        BYTES.set(0L);
        PAYLOADS.set(0L);
        LAST_PAYLOAD_BYTES.set(0L);
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
