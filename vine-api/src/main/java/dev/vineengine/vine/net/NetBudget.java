package dev.vineengine.vine.net;

/**
 * The engine's payload size budget (sub-05 Stage E, §2 internals):
 * <ul>
 *   <li>{@link #MAX_DIRECT_BYTES} — a payload at or below this travels as one frame;</li>
 *   <li>above it and at or below {@link #MAX_CHUNKED_BYTES} — split into
 *       {@link #CHUNK_FRAME_BYTES} frames on {@code vine:chunk}, reassembled before delivery;</li>
 *   <li>above {@link #MAX_CHUNKED_BYTES} — rejected at encode, never on the wire;</li>
 *   <li>{@link #MAX_REASSEMBLY_BYTES} — a receiver's per-player reassembly cap,
 *       with {@link #REASSEMBLY_TIMEOUT_SECONDS} before a partial set is dropped.</li>
 * </ul>
 */
public final class NetBudget {

    public static final int MAX_DIRECT_BYTES = 256 * 1024;
    public static final int MAX_CHUNKED_BYTES = 4 * 1024 * 1024;
    public static final int CHUNK_FRAME_BYTES = 16 * 1024;
    public static final int MAX_REASSEMBLY_BYTES = 8 * 1024 * 1024;
    public static final int REASSEMBLY_TIMEOUT_SECONDS = 30;

    private NetBudget() {
    }
}
