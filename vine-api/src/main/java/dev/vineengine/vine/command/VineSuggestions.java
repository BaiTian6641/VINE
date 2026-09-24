package dev.vineengine.vine.command;

import java.util.ArrayList;
import java.util.List;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.internal.PlayerNames;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.session.VineSession;
import dev.vineengine.vine.session.VineSessions;

/**
 * The engine's default completion sources (sub-06 Stage C) — what the engine
 * argument types suggest when a consumer declares nothing of its own. They read
 * live engine state (registrations, sessions), so a suggested value is always
 * one the command can actually resolve.
 *
 * <p>All of them are computed server-side and delivered through the cell's
 * native provider: an unmodded vanilla client tab-completes exactly like a
 * modded one (§2 vanilla-client sync).
 */
public final class VineSuggestions {

    private VineSuggestions() {
    }

    /**
     * Every id registered with the engine ({@code vine_test:testblock}, …), the
     * natural completion for {@link VineArgumentTypes#VINE_ID}.
     */
    public static final SuggestionSource REGISTERED_IDS = context -> {
        List<String> out = new ArrayList<>();
        for (String key : VineRegistries.idMap().keySet()) {
            // Map keys are "<registryId> <entryId>" (sub-02 runtime ids); the
            // completion a command wants is the entry id itself.
            String entryId = key.substring(key.lastIndexOf(' ') + 1);
            if (context.matches(entryId)) {
                out.add(entryId);
            }
        }
        java.util.Collections.sort(out);
        return List.copyOf(new java.util.LinkedHashSet<>(out));
    };

    /**
     * Participants of the executing player's session, the completion for
     * {@link VineArgumentTypes#PLAYER_IN_SESSION}. Empty for a console source
     * (no session) and when the cell's sessions subsystem is absent — the
     * selector still parses any player, so the command degrades, never breaks.
     */
    public static final SuggestionSource SESSION_PLAYERS = context -> {
        if (!(context.source().player().orElse(null) instanceof VinePlayer player)) {
            return List.of();
        }
        java.util.UUID self = player.uniqueId();
        for (VineId sessionId : liveSessionIds()) {
            VineSession session = VineSessions.manager().get(sessionId).orElse(null);
            if (session == null || !session.participants().contains(self)) {
                continue;
            }
            List<String> names = new ArrayList<>();
            for (java.util.UUID participant : session.participants()) {
                String name = PlayerNames.nameOf(participant);
                if (name != null && context.matches(name)) {
                    names.add(name);
                }
            }
            java.util.Collections.sort(names);
            return names;
        }
        return List.of();
    };

    private static List<VineId> liveSessionIds() {
        return new ArrayList<>(VineSessions.manager().liveSessions());
    }
}
