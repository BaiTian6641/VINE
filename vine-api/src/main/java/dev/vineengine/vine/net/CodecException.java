package dev.vineengine.vine.net;

/**
 * Raised by {@link VineBuf} reads and {@link PayloadCodec}s on truncated,
 * oversized, or malformed input (sub-05 §4: hostile clients own every byte —
 * a bad payload is a codec exception and a dropped message, never a crash or
 * a large allocation).
 */
public class CodecException extends RuntimeException {

    public CodecException(String message) {
        super(message);
    }

    public CodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
