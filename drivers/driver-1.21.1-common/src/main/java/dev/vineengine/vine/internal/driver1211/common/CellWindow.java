package dev.vineengine.vine.internal.driver1211.common;

import dev.vineengine.vine.internal.spi.UnsupportedCellException;

/**
 * The 1.21.1 cell's supported data-version window (§5.12). Cells are matched by
 * {@code SharedConstants} data version, never version strings: this window is the
 * only compatibility statement the 1.21.1 drivers make.
 *
 * <p>The window is exactly Minecraft 1.21.1 (data version 3955). It is loader-neutral
 * — both 1.21.1 drivers probe the same number through their own mappings
 * ({@code WorldVersion.getDataVersion()} Mojmap vs. {@code GameVersion.getSaveVersion()}
 * Yarn) and check it here.
 */
public final class CellWindow {

    /** Minecraft 1.21.1 data version (inclusive window bounds). */
    public static final int MIN_DATA_VERSION = 3955;
    public static final int MAX_DATA_VERSION = 3955;

    private CellWindow() {
    }

    /**
     * Throws {@link UnsupportedCellException} — the designed clean boot failure — when
     * the probed runtime data version is outside this cell's window.
     */
    public static void check(String driverId, int probedDataVersion) {
        if (probedDataVersion < MIN_DATA_VERSION || probedDataVersion > MAX_DATA_VERSION) {
            throw new UnsupportedCellException(driverId, probedDataVersion, MIN_DATA_VERSION, MAX_DATA_VERSION);
        }
    }
}
