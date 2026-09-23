package dev.vineengine.vine.session;

import dev.vineengine.vine.data.VoxelData;

/**
 * Public session-scoped state (sub-14 §2) — replicated to all participants in
 * the M2 replication stage; server-authoritative always.
 *
 * @param phase current lifecycle phase
 * @param ticksRemaining countdown for timed sessions; {@code 0} = untimed
 * @param objectives engine-owned tree (objective progress lives here)
 * @param sharedFlags public flags (e.g. "bossSpawned")
 */
public record SessionState(SessionPhase phase, long ticksRemaining,
        VoxelData objectives, VoxelData sharedFlags) {
}
