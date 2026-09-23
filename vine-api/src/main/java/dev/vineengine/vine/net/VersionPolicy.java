package dev.vineengine.vine.net;

/**
 * How a channel's protocol version is negotiated between client and server
 * (sub-05 §2). The negotiation itself is the stage-C configuration-phase
 * handshake; this enum only declares intent at registration time.
 */
public enum VersionPolicy {
    /** Protocol versions must match exactly; a mismatch disconnects with an engine reason. */
    REQUIRE_MATCH,
    /** A mismatch disables this channel for that connection; every other channel is unaffected. */
    OPTIONAL,
    /** The server's protocol wins; the client adapts or the channel is disabled. */
    SERVER_AUTHORITATIVE
}
