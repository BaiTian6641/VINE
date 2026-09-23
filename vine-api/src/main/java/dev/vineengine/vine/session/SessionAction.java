package dev.vineengine.vine.session;

import java.util.UUID;

import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;

/**
 * One submitted session action (sub-14 §2): a participant asks the session to
 * do something; only {@link SessionRules#validate} decides. Carries an opaque
 * payload tree so action shapes stay consumer-defined.
 *
 * @param player the submitting participant
 * @param kind action identity (consumer-namespaced VineId)
 * @param payload optional action data (created via {@code VineData.create})
 */
public record SessionAction(UUID player, VineId kind, VoxelData payload) {
}
