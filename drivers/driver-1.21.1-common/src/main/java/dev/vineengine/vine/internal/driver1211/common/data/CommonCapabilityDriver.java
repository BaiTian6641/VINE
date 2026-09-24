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
 * <p>{@code queryNative} answers from that carrier: the holder payload is a
 * *bundle* (one entry per schema — an entity may carry engine data and a
 * capability state at once), so the query picks the capability's own slice via
 * the engine's schema derivation and hands it back as an engine tree, or reports
 * absent — it never throws. All four scopes have attach points since sub-03
 * Stage E: item stacks carry the component payload, block entities, entities and
 * players the cell's attachment payload, so no scope needs a Mixin.
 */
public final class CommonCapabilityDriver implements CapabilityDriver {

    private static final Logger LOG = LoggerFactory.getLogger(CommonCapabilityDriver.class);

    private final Function<Object, byte[]> payloadReader;
    private final Set<String> exposed = ConcurrentHashMap.newKeySet();

    public CommonCapabilityDriver(Function<Object, byte[]> payloadReader) {
        this.payloadReader = java.util.Objects.requireNonNull(payloadReader, "payloadReader");
    }

    /** Which (scope, type) pairs this cell has exposed — the TCK's visibility probe. */
    public Set<String> exposed() {
        return Set.copyOf(exposed);
    }

    @Override
    public <T> void exposeNative(CapabilityScope scope, VineId typeId, Class<T> apiClass) {
        // Every scope has a carrier now: item stacks through the engine component,
        // block entities / entities / players through the cell's attachment payload
        // (sub-03 Stage E). Exposure is a declaration: the query path below reads
        // whichever holder the target names.
        exposed.add(scope + "/" + typeId);
        LOG.info("[VINE] caps: exposed {} {} (carrier: {} payload)", typeId, scope,
            scope == CapabilityScope.ITEM ? "item" : "attachment");
    }

    @Override
    public Object queryNative(VineId nativeId, CapabilityTarget target) {
        Object holder = holderOf(target);
        if (holder == null) {
            return null;
        }
        byte[] payload = payloadReader.apply(holder);
        if (payload == null || payload.length == 0) {
            return null;
        }
        try {
            // One holder, several schemas: the carrier is a bundle, and this query
            // answers for the capability's own slice.
            VineId schemaId = dev.vineengine.vine.internal.capability.CapabilityStore.schemaIdFor(nativeId);
            byte[] slice = dev.vineengine.vine.internal.data.VoxelBlobCodec
                .loadBundle(payload, schemaId).get(schemaId);
            return slice == null ? null : VineData.decode(slice);
        } catch (RuntimeException undecodable) {
            // Foreign or stale payload: absence, never an exception.
            return null;
        }
    }

    /** The native holder a capability target names, or {@code null} for a scope without one. */
    private static Object holderOf(CapabilityTarget target) {
        if (target instanceof CapabilityTarget.ItemCapabilityTarget item) {
            return item.raw();
        }
        if (target instanceof CapabilityTarget.BlockCapabilityTarget block) {
            return block.blockEntity();
        }
        if (target instanceof CapabilityTarget.EntityCapabilityTarget entity) {
            return entity.entity();
        }
        if (target instanceof CapabilityTarget.PlayerCapabilityTarget player) {
            return player.player();
        }
        return null;
    }
}
