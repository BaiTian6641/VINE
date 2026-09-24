package dev.vineengine.vine.command;

import java.util.Objects;

/**
 * Opaque, typed reference to a command argument type (sub-06 §2). Carries no
 * Brigadier or loader type — the Prime Invariant holds for the whole command
 * DSL; drivers translate each ref to the native argument type mechanically.
 *
 * <p>Instances are engine-provided singletons from {@link VineArgumentTypes};
 * consumers never construct their own. Identity comparison is valid.
 *
 * <p><b>Invariant (locked, sub-06 §4):</b> engine argument types
 * ({@code VINE_ID}, {@code VOXEL_PATH}, {@code PLAYER_IN_SESSION} — Stage C)
 * are server-parsed only and never appear in the synced command tree; each maps
 * to the nearest vanilla type so unmodded vanilla clients tab-complete without
 * a client mod.
 *
 * @param <T> the Java type the parsed argument value is delivered as
 */
public final class ArgumentTypeRef<T> {

    private final String id;
    private final Class<T> type;
    private final java.util.List<String> enumValues;

    ArgumentTypeRef(String id, Class<T> type) {
        this(id, type, java.util.List.of());
    }

    ArgumentTypeRef(String id, Class<T> type, java.util.List<String> enumValues) {
        this.id = Objects.requireNonNull(id, "id");
        this.type = Objects.requireNonNull(type, "type");
        this.enumValues = java.util.List.copyOf(enumValues);
    }

    /** Enum constants for {@code enum(...)} types, in declaration order (empty otherwise). */
    public java.util.List<String> enumValues() {
        return enumValues;
    }

    /** Stable engine id of the argument type (diagnostics, conflict reports, JSON descriptors). */
    public String id() {
        return id;
    }

    /** The Java type parsed values of this argument are delivered as. */
    public Class<T> type() {
        return type;
    }

    @Override
    public String toString() {
        return "ArgumentTypeRef[" + id + "]";
    }
}
