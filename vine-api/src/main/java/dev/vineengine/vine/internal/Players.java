package dev.vineengine.vine.internal;

import java.util.UUID;
import java.util.function.Function;

import dev.vineengine.vine.VinePlayer;

/**
 * The engine's UUID → player lookup seam (sub-14 Stage C). Drivers install the
 * cell's resolver at server start; the engine needs it wherever a payload has to
 * reach a specific participant and only the UUID is known (session snapshots),
 * because resolving a player is a per-cell concern and no game type crosses into
 * the engine (Prime Invariant).
 *
 * <p>Unset means "no such player reachable" — callers treat that as "not
 * connected", never as an error.
 */
public final class Players {

    private static volatile Function<UUID, VinePlayer> resolver;

    private Players() {
    }

    /** Installs the cell's resolver ({@code null} clears it — cell teardown, tests). */
    public static void install(Function<UUID, VinePlayer> byId) {
        resolver = byId;
    }

    /** The connected player with this id, or {@code null} when none is reachable. */
    public static VinePlayer byId(UUID uniqueId) {
        Function<UUID, VinePlayer> current = resolver;
        return current == null || uniqueId == null ? null : current.apply(uniqueId);
    }
}
