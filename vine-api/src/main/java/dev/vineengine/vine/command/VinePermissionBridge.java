package dev.vineengine.vine.command;

/**
 * Resolves {@link VinePermission.Node} gates for a cell or plugin (sub-06 Stage
 * D). The engine consults it before any native provider, which is what lets a
 * permission plugin (or a test) decide node policy without touching a loader
 * API — the Prime Invariant holds for permissions exactly as it does for
 * everything else.
 *
 * <p>Implementations run on the server thread for every gated command dispatch
 * (and for the tree build), so they must be cheap and non-blocking.
 */
@FunctionalInterface
public interface VinePermissionBridge {

    /**
     * Whether {@code source} holds {@code node}.
     *
     * @param fallbackLevel the descriptor's fallback op level — the bridge may
     *        ignore it (a provider that owns the node) or honor it (a provider
     *        that only layers defaults)
     */
    boolean has(CommandSourceRef source, String node, int fallbackLevel);
}
