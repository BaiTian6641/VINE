package dev.vineengine.vine.internal.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.net.Endpoint;
import dev.vineengine.vine.net.MessageHandler;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.Validator;
import dev.vineengine.vine.registry.VineId;

/**
 * The engine's channel + message table (sub-05 §2 internals):
 * {@code VineId → ChannelSpec + message table}, plus the wire-id reverse index
 * used by inbound dispatch.
 *
 * <p>Registration closes when the engine enters {@code REGISTRIES_FROZEN}
 * (sub-01 phase rule) — late registration throws, never silently drops.
 * Dormant with zero consumers: no transport traffic, no listeners (Minimal
 * Footprint). Registration is effectively single-threaded (loader init);
 * methods are synchronized so the freeze transition cannot race a write.
 */
final class ChannelRegistry {

    private final Map<VineId, ChannelImpl> channels = new LinkedHashMap<>();
    private final Map<VineId, MessageEntry> byWireId = new HashMap<>();
    private boolean frozen;

    synchronized boolean frozen() {
        return frozen;
    }

    /** Idempotent; every write from here on throws. */
    synchronized void freeze() {
        frozen = true;
    }

    /**
     * Returns the channel for {@code spec}, registering it on first sight.
     * Idempotent per equal spec; an equal id with a conflicting spec throws.
     */
    synchronized ChannelImpl channel(ChannelSpec spec, VineNetImpl net) {
        ChannelImpl existing = channels.get(spec.id());
        if (existing != null) {
            if (!existing.spec().equals(spec)) {
                throw new IllegalStateException("channel " + spec.id()
                    + " already registered with conflicting spec " + existing.spec());
            }
            return existing;
        }
        checkWritable("register channel " + spec.id());
        ChannelImpl created = new ChannelImpl(spec, this, net);
        channels.put(spec.id(), created);
        return created;
    }

    /**
     * Registers one message on {@code channel}. The first registration fixes
     * the id's type and codec; a second call may add the other endpoint's
     * handler but must carry an equal type and the same codec instance.
     */
    synchronized <P> void registerMessage(ChannelImpl channel, VineId id, Class<P> type,
                                          PayloadCodec<P> codec, Endpoint endpoint,
                                          MessageHandler<P> handler) {
        registerMessage(channel, id, type, codec, endpoint, List.of(), handler);
    }

    /** Validator-chain overload (sub-05 Stage D); validators are C2S-only. */
    synchronized <P> void registerMessage(ChannelImpl channel, VineId id, Class<P> type,
                                          PayloadCodec<P> codec, Endpoint endpoint,
                                          List<Validator<P>> validators, MessageHandler<P> handler) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(validators, "validators");
        if (!validators.isEmpty() && endpoint != Endpoint.SERVER) {
            throw new IllegalStateException("validators guard inbound client payloads; message " + id
                + " declares them for " + endpoint + " (a local payload needs no untrusted-input policy)");
        }
        checkWritable("register message " + id);
        VineId wireId = wireId(channel.spec().id(), id);
        MessageEntry entry = byWireId.get(wireId);
        if (entry == null) {
            entry = new MessageEntry(id, wireId, type, codec);
            byWireId.put(wireId, entry);
            channel.add(entry);
        } else if (!entry.type.equals(type) || entry.codec != codec) {
            throw new IllegalStateException("message " + id + " on channel " + channel.spec().id()
                + " re-registered with a different type or codec");
        }
        if (!validators.isEmpty()) {
            entry.addValidators(validators);
        }
        entry.addHandler(endpoint, handler);
    }

    /** The message behind {@code wireId}, or {@code null} — unknown wire ids are dropped, not errors. */
    synchronized MessageEntry byWireId(VineId wireId) {
        return byWireId.get(wireId);
    }

    /** Wire identity: {@code <channel-ns>:<channel-path>/<message-path>}. */
    private static VineId wireId(VineId channelId, VineId messageId) {
        return VineId.of(channelId.namespace(), channelId.path() + "/" + messageId.path());
    }

    /** Every registered channel, in registration order. */
    synchronized Iterable<ChannelImpl> channels() {
        return List.copyOf(channels.values());
    }

    /** One {@code MessageSpec} per (message, handler-endpoint) direction on {@code channel}. */
    synchronized List<NetDriver.MessageSpec> messageSpecs(ChannelImpl channel) {
        List<NetDriver.MessageSpec> messages = new ArrayList<>();
        for (MessageEntry entry : channel.messages()) {
            for (Map.Entry<Endpoint, MessageHandler<Object>> direction : entry.directions()) {
                messages.add(new NetDriver.MessageSpec(entry.wireId, direction.getKey()));
            }
        }
        return List.copyOf(messages);
    }

    /** Pushes every channel's full registration table to {@code transport}. */
    synchronized void pushTo(NetDriver transport) {
        for (ChannelImpl channel : channels.values()) {
            transport.register(channel.spec(), messageSpecs(channel));
        }
    }

    private void checkWritable(String action) {
        if (frozen) {
            throw new IllegalStateException("cannot " + action
                + " — channel registration closed at REGISTRIES_FROZEN");
        }
    }
}
