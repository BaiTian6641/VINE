package dev.vineengine.vine.command;

/**
 * The engine's command argument types (sub-06 §2). Stage A ships only
 * {@link #STRING} — the remaining vanilla mirrors ({@code INT}, {@code LONG},
 * {@code DOUBLE}, {@code BOOL}, {@code GREEDY}, {@code ENUM}) land with the
 * full descriptor DSL in Stage B, and the engine types ({@code VINE_ID},
 * {@code VOXEL_PATH}, {@code PLAYER_IN_SESSION}) with Stage C.
 *
 * <p>Registering a descriptor that uses a type the current stage does not
 * support fails explicitly at registration (compile time in the engine
 * compiler), never at dispatch.
 */
public final class VineArgumentTypes {

    private VineArgumentTypes() {
    }

    /** A single-word string argument (Brigadier {@code string()} semantics on every cell). */
    public static final ArgumentTypeRef<String> STRING = new ArgumentTypeRef<>("string", String.class);

    /** A 32-bit signed integer (Brigadier {@code integer()} semantics). */
    public static final ArgumentTypeRef<Integer> INT = new ArgumentTypeRef<>("int", Integer.class);

    /** A 64-bit signed integer (Brigadier {@code longArg()} semantics). */
    public static final ArgumentTypeRef<Long> LONG = new ArgumentTypeRef<>("long", Long.class);

    /** A double-precision number (Brigadier {@code doubleArg()} semantics). */
    public static final ArgumentTypeRef<Double> DOUBLE = new ArgumentTypeRef<>("double", Double.class);

    /** A boolean literal {@code true}/{@code false}. */
    public static final ArgumentTypeRef<Boolean> BOOL = new ArgumentTypeRef<>("bool", Boolean.class);

    /** The rest of the line as one string (Brigadier {@code greedyString()} semantics). */
    public static final ArgumentTypeRef<String> GREEDY = new ArgumentTypeRef<>("greedy", String.class);

    /**
     * An enumeration of {@code type}'s constants, in declaration order — the
     * compiler maps it to the native enum argument and parses by constant name
     * (case-insensitive), so consumers get a typed value and suggest nothing by
     * default (mirror-enums are self-suggesting on every cell).
     */
    /**
     * The data-driven spelling of {@link #enumeration(Class)} (sub-06 Stage B):
     * constants come from the descriptor file, and the parsed value is the
     * chosen constant's name (no consumer class is reachable from data).
     * Internal to the JSON descriptor path — consumers use the typed form.
     */
    public static ArgumentTypeRef<String> jsonEnumeration(String name, java.util.List<String> values) {
        return new ArgumentTypeRef<>("enum:" + name, String.class, values);
    }

    public static <E extends Enum<E>> ArgumentTypeRef<E> enumeration(Class<E> type) {
        java.util.List<String> values = java.util.Arrays.stream(type.getEnumConstants())
            .map(Enum::name)
            .toList();
        return new ArgumentTypeRef<>("enum:" + type.getSimpleName(), type, values);
    }
}
