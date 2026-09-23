package dev.vineengine.vine.internal.driver1211.common.net;

import java.util.UUID;

import dev.vineengine.vine.VinePlayer;

/**
 * The driver's {@link VinePlayer}: a value facade over the native player's stable
 * id and display name. Created at inbound-delivery time from the native connection;
 * sends resolve back to the native player through the server's player list by
 * {@link #uniqueId()} (never by name — names are mutable).
 */
public record DriverVinePlayer(UUID uniqueId, String name) implements VinePlayer {
}
