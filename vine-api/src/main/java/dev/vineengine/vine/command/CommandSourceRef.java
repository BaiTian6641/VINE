package dev.vineengine.vine.command;

import java.util.Optional;

import dev.vineengine.vine.VinePlayer;

/**
 * Version-free facade over whoever/whatever executed a command (sub-06 §2):
 * player, console, command block, or an automation source — the consumer never
 * observes which loader or Minecraft version backs it.
 *
 * <p><b>Invariant:</b> {@link #player()} is present exactly when
 * {@link #isPlayer()} is true.
 *
 * <p>Stage A surface: name + player identity. A {@code server()} accessor lands
 * with the engine server facade; permission levels are intentionally absent —
 * gates are declared on the descriptor and enforced before the executor runs,
 * never re-checked by hand in consumer code.
 */
public interface CommandSourceRef {

    /** The source's display name (player name, {@code "Server"} for the console, …). */
    String name();

    /** Whether the source is a player in the world. */
    boolean isPlayer();

    /** The executing player; empty for console/command-block/automation sources. */
    Optional<VinePlayer> player();
}
