package dev.vineengine.vine.command;

import java.util.List;

/**
 * Completion candidates for one argument (sub-06 Stage B). The engine computes
 * suggestions server-side and hands them to the cell's native provider, so
 * vanilla clients tab-complete without a client mod (§4).
 *
 * <p>Implementations must be pure and fast: they run on the server thread for
 * every completion request, and must never block.
 */
@FunctionalInterface
public interface SuggestionSource {

    /** Candidates for {@code context.partial()}, best-effort (may be empty). */
    List<String> suggest(SuggestionContext context);

    /** A source that offers a fixed list, filtered by the typed prefix. */
    static SuggestionSource of(List<String> candidates) {
        List<String> copy = List.copyOf(candidates);
        return context -> copy.stream().filter(context::matches).toList();
    }
}
