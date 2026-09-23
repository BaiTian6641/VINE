package dev.vineengine.vine.internal.driver1211.fabric.net;

import net.minecraft.network.packet.CustomPayload;

/**
 * The Fabric cell's single wire-payload shape (sub-05 Stage A): an opaque byte
 * array under a per-message {@link CustomPayload.Id} — engine wire ids map 1:1 to
 * payload ids and bytes move untouched across the transport seam (the codec DSL
 * never crosses it; {@code VineBuf} stays engine-side).
 *
 * <p>Yarn names the contract method {@code getId()} — implemented explicitly over
 * the record component (loader difference absorbed; Mojmap's
 * {@code CustomPacketPayload} uses {@code type()}, which the record accessor
 * satisfies on the NF cell).
 */
public record VineFabricPayload(CustomPayload.Id<VineFabricPayload> id, byte[] data) implements CustomPayload {

    @Override
    public CustomPayload.Id<VineFabricPayload> getId() {
        return id;
    }
}
