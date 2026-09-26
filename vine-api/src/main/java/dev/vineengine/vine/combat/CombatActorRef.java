package dev.vineengine.vine.combat;

import java.util.UUID;

import dev.vineengine.vine.entity.VineEntityRef;

/**
 * Who is swinging (sub-10 Stage D): an engine actor or a player.
 *
 * <p>The distinction is forced by the world, not chosen: a player is not a spawned
 * {@code vine:entity}, so it has no {@link VineEntityRef}, and a combat API that only spoke
 * of actors would have no way to say "this player hit that beast" — which is the whole
 * point of the pipeline. Both cases carry identity only; everything else about the attacker
 * (position, facing, held item) is passed in, because the engine never keeps a second copy
 * of a cell's state.
 *
 * <p>Sealed on purpose: two kinds today, and a third kind would be a new decision about
 * identity rather than an accident of an open interface.
 */
public sealed interface CombatActorRef permits CombatActorRef.Actor, CombatActorRef.Player {

    /** An engine-spawned actor. */
    record Actor(VineEntityRef ref) implements CombatActorRef {

        public Actor {
            java.util.Objects.requireNonNull(ref, "ref");
        }

        @Override
        public String toString() {
            return "actor:" + ref;
        }
    }

    /** A player, identified the way every other player-scoped API identifies one. */
    record Player(UUID player) implements CombatActorRef {

        public Player {
            java.util.Objects.requireNonNull(player, "player");
        }

        @Override
        public String toString() {
            return "player:" + player;
        }
    }

    /** A player attacker. */
    static CombatActorRef player(UUID player) {
        return new Player(player);
    }

    /** An engine-actor attacker. */
    static CombatActorRef actor(VineEntityRef ref) {
        return new Actor(ref);
    }

    /** The player id behind this attacker, empty for an actor. */
    default java.util.Optional<UUID> playerId() {
        return this instanceof Player player ? java.util.Optional.of(player.player()) : java.util.Optional.empty();
    }
}
