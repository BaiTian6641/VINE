package dev.vineengine.vine.session;

import java.util.UUID;

import dev.vineengine.vine.data.VoxelData;

/**
 * Per-participant replicated state (sub-14 §2): delivered to the owning player
 * (all players when the session type opts in). Server-authoritative.
 *
 * @param player participant identity
 * @param contribution what the player contributed (public among participants)
 * @param loadout session-scoped loadout data
 * @param progress private progression data (e.g. quest steps)
 */
public record ParticipantState(UUID player, VoxelData contribution, VoxelData loadout,
        VoxelData progress) {
}
