package dev.vineengine.vine.net;

import java.util.Objects;

import dev.vineengine.vine.registry.VineId;

/**
 * Identity and versioning of one engine channel (sub-05 §2). Channels are
 * descriptors (data), not behavior: two calls to
 * {@link VineNet#channel(ChannelSpec)} with an equal spec return the same
 * channel; an equal id with a different {@code protocol}/{@code policy} is a
 * conflicting registration and throws.
 *
 * @param id channel id; wire payload ids are derived under its namespace
 * @param protocol channel protocol version, negotiated by the stage-C
 *        handshake; must be ≥ 1
 * @param policy how client/server version differences are resolved
 */
public record ChannelSpec(VineId id, int protocol, VersionPolicy policy) {

    public ChannelSpec {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(policy, "policy");
        if (protocol < 1) {
            throw new IllegalArgumentException("protocol must be >= 1: " + protocol);
        }
    }
}
