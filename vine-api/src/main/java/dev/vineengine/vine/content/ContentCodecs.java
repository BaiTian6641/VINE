package dev.vineengine.vine.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

import dev.vineengine.vine.registry.VineId;

/**
 * Shared codec plumbing for the content descriptors. Package-private on
 * purpose: {@link VineId} is sub-02's type and does not carry a Codec yet;
 * when its owning stage adds one, this field collapses to it and this file
 * disappears (documented seam, never public API).
 */
final class ContentCodecs {

    /**
     * {@link VineId} as its canonical {@code "namespace:path"} string;
     * rejects malformed ids as decode errors instead of throwing out of DFU.
     */
    static final Codec<VineId> VINE_ID = Codec.STRING.comapFlatMap(
        value -> {
            try {
                return DataResult.success(VineId.parse(value));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "malformed VineId: " + e.getMessage());
            }
        },
        VineId::toString);

    private ContentCodecs() {
    }
}
