package dev.vineengine.vine;

/**
 * Marker for every event flowing through the engine (bus events and phase changes).
 *
 * <p>Engine events are version-free by construction (Prime Invariant §5.1): payloads
 * expose JDK/engine types only, never {@code net.minecraft} or loader types.
 */
public interface VineEvent {
}
