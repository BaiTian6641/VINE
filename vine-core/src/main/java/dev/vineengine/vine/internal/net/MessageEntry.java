package dev.vineengine.vine.internal.net;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.MessageHandler;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.registry.VineId;

/**
 * One registered message: payload type, codec, and per-endpoint handlers
 * (sub-05 §2). The codec is handed in once at registration and reused for every
 * send/receive of the message — codecs are built once and cached (§2 internals).
 *
 * <p>The wire id is derived as {@code <channel-ns>:<channel-path>/<message-path>},
 * so message ids stay unique on the wire even when two channels reuse the same
 * message path.
 */
final class MessageEntry {

    final VineId id;
    final VineId wireId;
    final Class<?> type;
    final PayloadCodec<Object> codec;
    private final Map<Endpoint, MessageHandler<Object>> handlers = new EnumMap<>(Endpoint.class);
    private final java.util.List<dev.vineengine.vine.net.Validator<Object>> validators = new java.util.ArrayList<>();

    @SuppressWarnings("unchecked")
    MessageEntry(VineId id, VineId wireId, Class<?> type, PayloadCodec<?> codec) {
        this.id = Objects.requireNonNull(id, "id");
        this.wireId = Objects.requireNonNull(wireId, "wireId");
        this.type = Objects.requireNonNull(type, "type");
        this.codec = (PayloadCodec<Object>) Objects.requireNonNull(codec, "codec");
    }

    /**
     * Attaches {@code handler} for {@code endpoint}. One handler per endpoint:
     * a second registration for the same endpoint is a conflicting-registration
     * bug and throws.
     */
    @SuppressWarnings("unchecked")
    void addHandler(Endpoint endpoint, MessageHandler<?> handler) {
        if (handlers.putIfAbsent(endpoint, (MessageHandler<Object>) handler) != null) {
            throw new IllegalStateException(
                "message " + id + " already has a " + endpoint + " handler");
        }
    }

    MessageHandler<Object> handler(Endpoint endpoint) {
        return handlers.get(endpoint);
    }

    /** Attaches the C2S validation chain (sub-05 Stage D); order is declaration order. */
    @SuppressWarnings("unchecked")
    void addValidators(java.util.List<? extends dev.vineengine.vine.net.Validator<?>> chain) {
        if (!validators.isEmpty()) {
            throw new IllegalStateException("message " + id + " already declares validators");
        }
        for (dev.vineengine.vine.net.Validator<?> validator : chain) {
            validators.add((dev.vineengine.vine.net.Validator<Object>) validator);
        }
    }

    java.util.List<dev.vineengine.vine.net.Validator<Object>> validators() {
        return java.util.List.copyOf(validators);
    }

    /** The handler endpoints registered so far — one {@code MessageSpec} per direction. */
    Iterable<Map.Entry<Endpoint, MessageHandler<Object>>> directions() {
        return handlers.entrySet();
    }
}
