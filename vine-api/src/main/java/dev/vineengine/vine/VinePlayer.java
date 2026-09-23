package dev.vineengine.vine;

import java.util.UUID;

/**
 * The engine's version-free player facade — the ONLY player-facade name in the
 * engine (shared vocabulary, docs/README.md); every subsystem references this type,
 * never redefines it.
 *
 * <p>Stage A sketch uses plain JDK/String ids only; richer identity types ride
 * sub-02's {@code VineId} once it lands.
 */
public interface VinePlayer {

    /**
     * The player's stable Mojang-assigned id.
     */
    UUID uniqueId();

    /**
     * The player's current display name. Names are mutable across sessions;
     * {@link #uniqueId()} is the identity.
     */
    String name();
}
