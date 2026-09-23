package dev.vineengine.vine.internal.driver1211.neoforge.net;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The NeoForge cell's single wire-payload shape (sub-05 Stage A): an opaque byte
 * array under a per-message {@link Type} — engine wire ids map 1:1 to payload
 * types and bytes move untouched across the transport seam (the codec DSL never
 * crosses it; {@code VineBuf} stays engine-side).
 */
public record VineNeoForgePayload(CustomPacketPayload.Type<VineNeoForgePayload> type, byte[] data)
        implements CustomPacketPayload {
}
