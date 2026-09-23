package dev.vineengine.vine.net;

/**
 * The side a message handler runs on (sub-05 §2). {@link Endpoint#SERVER}
 * handlers receive C2S payloads, {@link Endpoint#CLIENT} handlers receive S2C
 * payloads; one message id may carry a handler for each endpoint (echo-style
 * request/response) via two {@link Channel#message} calls.
 */
public enum Endpoint {
    /** Handler runs on the (integrated or dedicated) server. */
    SERVER,
    /** Handler runs on the client. */
    CLIENT
}
