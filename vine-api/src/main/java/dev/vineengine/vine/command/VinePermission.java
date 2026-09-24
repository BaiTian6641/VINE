package dev.vineengine.vine.command;

/**
 * A permission gate on a command node (sub-06 §2). Always evaluated
 * <b>server-side</b>, on the native command source, before the executor runs —
 * a denied source never reaches engine code.
 *
 * <p>Sealed so the bridging policy stays exhaustive: {@link Level} maps to
 * Brigadier's {@code requires(source -> source.hasPermission(n))} on every cell;
 * {@link Node} resolves through, in order, an engine-registered
 * {@link VinePermissionBridge}, the cell's native permission provider when one
 * is present (probed, never a hard dependency), and finally its own
 * {@link Node#fallbackLevel()} as a vanilla op-level check.
 *
 * <p>Why the ordering is not "native first": NeoForge 1.21 dropped string
 * permission nodes in favour of typed nodes registered at startup
 * ({@code PermissionAPI.getRegisteredNodes()}, handler identified by
 * {@code getActivePermissionHandler()}), and consumers under the Prime
 * Invariant cannot mint loader types — so on that family a node string resolves
 * through the engine bridge or the fallback, and the native probe reports
 * "no provider" instead of pretending to check something. Fabric's
 * fabric-permissions-api does accept arbitrary node strings, so there the
 * native provider is consulted directly when the mod is present.
 */
public sealed interface VinePermission {

    /**
     * Vanilla op-level gate: sources with permission level ≥ {@code level} pass.
     * Levels follow the vanilla scale 0–4; anything outside is rejected at
     * construction, never at dispatch.
     */
    static Level level(int level) {
        return new Level(level);
    }

    /**
     * Vanilla op-level gate (0–4). Identical semantics on both loader families —
     * parity is asserted by the TCK command-execution scenario.
     */
    record Level(int level) implements VinePermission {

        public Level {
            if (level < 0 || level > 4) {
                throw new IllegalArgumentException("vanilla op level must be 0..4: " + level);
            }
        }
    }

    /**
     * A named permission node with an op-level fallback (sub-06 Stage D).
     *
     * @param node the permission node a permission provider resolves
     * @param fallbackLevel vanilla op level used when no provider resolves the
     *        node — the same 0–4 scale as {@link Level}
     */
    static Node node(String node, int fallbackLevel) {
        return new Node(node, fallbackLevel);
    }

    record Node(String node, int fallbackLevel) implements VinePermission {

        public Node {
            java.util.Objects.requireNonNull(node, "node");
            if (node.isEmpty()) {
                throw new IllegalArgumentException("permission node must not be empty");
            }
            if (fallbackLevel < 0 || fallbackLevel > 4) {
                throw new IllegalArgumentException("fallback op level must be 0..4: " + fallbackLevel);
            }
        }
    }
}
