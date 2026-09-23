package dev.vineengine.vine.internal.spi;

/**
 * Explicit boot failure thrown by a driver whose supported data-version window does
 * not contain the running game's {@code SharedConstants} data version (§5.12).
 *
 * <p>This is the designed failure mode for an out-of-window runtime — not a silent
 * misfire: the message names the driver, the probed data version, and the supported
 * window. Cells are matched by data version and runtime probes, never by parsing
 * version strings.
 */
public final class UnsupportedCellException extends RuntimeException {

    public UnsupportedCellException(String driverId, int dataVersion, int minSupported, int maxSupported) {
        super("VINE driver '" + driverId + "' does not support this runtime: SharedConstants data version "
            + dataVersion + " is outside its supported window [" + minSupported + ", " + maxSupported + "]"
            + " (cells matched by data version, never version strings — §5.12)");
    }
}
