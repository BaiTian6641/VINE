package dev.vineengine.vine.internal;

import java.util.UUID;
import java.util.function.Function;

/**
 * The engine's UUID → player-name lookup seam (sub-06 Stage C). Drivers install
 * the cell's resolver at boot (server player list); the engine's suggestion
 * sources use it to name session participants, which the engine itself only
 * knows as UUIDs (sub-14).
 *
 * <p>Unset means "no names available" — suggestions degrade to empty, never throw.
 */
public final class PlayerNames {

    private static volatile Function<UUID, String> resolver;

    private PlayerNames() {
    }

    /** Installs the cell's resolver ({@code null} clears it — tests, cell teardown). */
    public static void install(Function<UUID, String> playerNames) {
        resolver = playerNames;
    }

    /** The player's name, or {@code null} when unknown to this cell. */
    public static String nameOf(UUID uniqueId) {
        Function<UUID, String> current = resolver;
        return current == null ? null : current.apply(uniqueId);
    }
}
