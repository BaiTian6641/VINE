package dev.vineengine.vine.internal.driver1211.common.data;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.capability.CapabilityScope;
import dev.vineengine.vine.capability.CapabilityTarget;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.internal.spi.CapabilityDriver;
import dev.vineengine.vine.registry.VineId;

/**
 * Capability interop for the 1.21.1 cells (sub-04 Stage C/D). Exposing an engine
 * capability type marks it load-visible; the state itself rides the target's
 * attach point — the same opaque payload the storage driver writes — so foreign
 * code (and the engine's own later reads) see one carrier, and a copy of the
 * target carries the capability with it.
 *
 * <p>{@code queryNative} answers from that carrier: the payload is decoded
 * through the engine's load path and handed back as an engine tree, or reported
 * absent — it never throws. Loader-native attachment registration for
 * block/entity/player scopes is the Mixin wave's job (sub-18 Stage E); the SPI
 * shape does not change when it lands.
 */
public final class ItemStackCapabilityDriver implements CapabilityDriver {

    private static final Logger LOG = LoggerFactory.getLogger(ItemStackCapabilityDriver.class);

    private final Function<Object, byte[]> payloadReader;
    private final Set<String> exposed = ConcurrentHashMap.newKeySet();

    public ItemStackCapabilityDriver(Function<Object, byte[]> payloadReader) {
        this.payloadReader = java.util.Objects.requireNonNull(payloadReader, "payloadReader");
    }

    /** Which (scope, type) pairs this cell has exposed — the TCK's visibility probe. */
    public Set<String> exposed() {
        return Set.copyOf(exposed);
    }

    @Override
    public <T> void exposeNative(CapabilityScope scope, VineId typeId, Class<T> apiClass) {
        if (scope != CapabilityScope.ITEM) {
            // BE/entity/player exposure needs per-scope attach points (Mixin wave);
            // recording the intent without claiming visibility would be dishonest.
            LOG.warn("[VINE] caps: exposing {} on {} is not supported on this cell yet — not exposed",
                typeId, scope);
            return;
        }
        exposed.add(scope + "/" + typeId);
        LOG.info("[VINE] caps: exposed {} {} (carrier: item payload)", typeId, scope);
    }

    @Override
    public Object queryNative(VineId nativeId, CapabilityTarget target) {
        if (!(target instanceof CapabilityTarget.ItemCapabilityTarget item)) {
            return null;
        }
        byte[] payload = payloadReader.apply(item.raw());
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            // The carrier holds every engine tree for the target (capabilities and
            // other payloads alike); the engine's boundary decides what it accepts.
            return VineData.decode(payload);
        } catch (RuntimeException undecodable) {
            // Foreign or stale payload: absence, never an exception.
            return null;
        }
    }
}
