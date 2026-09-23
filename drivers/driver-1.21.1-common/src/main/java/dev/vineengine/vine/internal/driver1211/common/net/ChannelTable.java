package dev.vineengine.vine.internal.driver1211.common.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vineengine.vine.internal.spi.NetDriver;
import dev.vineengine.vine.net.ChannelSpec;
import dev.vineengine.vine.registry.VineId;

/**
 * The driver's latest-pushed channel registration table (sub-05 Stage A). The
 * engine pushes on every message registration and once with the final table at
 * {@code REGISTRIES_FROZEN}; per the {@link NetDriver#register} contract the
 * driver binds its loader payload types from the last call it can honor, so this
 * store replaces per channel id and the native bind walks {@link #entries()}.
 * Loader-neutral mechanics shared by both cells.
 */
public final class ChannelTable {

    private final Map<VineId, Entry> entries = new LinkedHashMap<>();

    /** One channel's latest registration. */
    public record Entry(ChannelSpec spec, List<NetDriver.MessageSpec> messages) {
    }

    /** Stores the latest table for {@code spec}'s channel (idempotent replacement). */
    public synchronized void put(ChannelSpec spec, List<NetDriver.MessageSpec> messages) {
        entries.put(spec.id(), new Entry(spec, List.copyOf(messages)));
    }

    /** Every channel's latest registration, in first-seen order. */
    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }
}
