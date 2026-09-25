package dev.vineengine.vine.content;

import java.util.Objects;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;
import dev.vineengine.vine.world.Direction;
import dev.vineengine.vine.world.Hand;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorld;

/**
 * The engine view of one block interaction (sub-07 Stage C): everything a
 * {@link UseBehavior} may legitimately need, in engine types only. A cell builds
 * one per native interaction and discards it afterwards — a context is never stored
 * by a behavior beyond the call it was given to.
 *
 * @param world  the world the interaction happened in
 * @param pos    the interacted block's position
 * @param state  the block's engine state at {@code pos} when the interaction began
 * @param player the interacting player
 * @param hand   which hand performed the interaction
 * @param face   the clicked face of the block
 * @param hit    where on the block the interaction landed, in world coordinates
 */
public record BlockUseContext(
        VineWorld world,
        BlockPos pos,
        BlockState state,
        VinePlayer player,
        Hand hand,
        Direction face,
        Vec3 hit) {

    public BlockUseContext {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(hand, "hand");
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(hit, "hit");
    }
}
