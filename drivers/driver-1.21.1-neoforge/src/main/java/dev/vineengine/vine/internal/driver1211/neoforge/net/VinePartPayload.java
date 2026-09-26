package dev.vineengine.vine.internal.driver1211.neoforge.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The 1.21.1 NeoForge cell's part-state envelope (sub-08 Stage D): which native body the
 * delta is about, plus the engine's own delta bytes untouched.
 *
 * <p><b>Why the actor rides the envelope.</b> The engine encodes a delta against an engine
 * actor ({@code VineEntityRef}) and counts its bytes; which native entity a client can
 * resolve that actor to is a cell fact, so the cell names it on the wire. The native id is
 * the one key a client's copy of a spawned body shares with the server's, which is what
 * makes a delta applyable without inventing an engine↔native id map on the client.
 *
 * <p>Shaped like {@link VineNeoForgePayload}: a per-message
 * {@link CustomPacketPayload.Type} plus opaque bytes, so nothing but engine bytes and one
 * native id crosses this seam.
 */
public record VinePartPayload(CustomPacketPayload.Type<VinePartPayload> type, int entityId, byte[] delta)
        implements CustomPacketPayload {
}
