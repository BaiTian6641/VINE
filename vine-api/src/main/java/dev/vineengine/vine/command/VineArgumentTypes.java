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
}
