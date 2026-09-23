package dev.vineengine.vine.data;

import java.util.Objects;

import dev.vineengine.vine.VinePlayer;

/**
 * One player attach point (sub-03 §2): the engine player facade identifies
 * the player on every cell — players already have their engine handle
 * (sub-01), so this target carries it directly, unlike the raw-object
 * interim targets.
 */
public record PlayerTarget(VinePlayer player) implements VoxelTarget {

    public PlayerTarget {
        Objects.requireNonNull(player, "player");
    }
}
