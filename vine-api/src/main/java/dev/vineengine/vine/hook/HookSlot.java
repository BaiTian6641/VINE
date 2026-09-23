package dev.vineengine.vine.hook;

import java.util.Objects;

import dev.vineengine.vine.VineEvent;

/**
 * A lazily-installed native hook (sub-01 Stage C, §5.8/Minimal Footprint §5.1):
 * the driver binds a loader-native source (event, callback, tap) to one
 * {@link VineEvent} type; vine-core installs that source when the <em>first</em>
 * handler for the type subscribes and uninstalls it when the <em>last</em> one
 * closes. Zero consumers therefore mean zero native listeners — vanilla
 * behavior down to the listener list.
 *
 * <p>Slots are declared by drivers and identified by {@link #id()} for logs and
 * the TCK's inventory; the type is what the engine counts subscriptions on.
 */
public final class HookSlot {

    private final String id;
    private final Class<? extends VineEvent> type;
    private final String source;

    private HookSlot(String id, Class<? extends VineEvent> type, String source) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.source = Objects.requireNonNull(source, "source");
    }

    /** Declares a slot: stable id, gated event type, native source description. */
    public static HookSlot of(String id, Class<? extends VineEvent> type, String source) {
        return new HookSlot(id, type, source);
    }

    /** Stable slot id (lowerCamelCase, e.g. {@code blockBreak}). */
    public String id() {
        return id;
    }

    /** The engine event type whose first/last subscription installs/uninstalls this hook. */
    public Class<? extends VineEvent> type() {
        return type;
    }

    /** Human-readable native source (loader event/callback), for logs and docs. */
    public String source() {
        return source;
    }

    @Override
    public String toString() {
        return "HookSlot[" + id + " -> " + type.getSimpleName() + " (" + source + ")]";
    }
}
