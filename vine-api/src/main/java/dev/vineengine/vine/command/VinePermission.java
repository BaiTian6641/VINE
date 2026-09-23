package dev.vineengine.vine.command;

/**
 * A permission gate on a command node (sub-06 §2). Always evaluated
 * <b>server-side</b>, on the native command source, before the executor runs —
 * a denied source never reaches engine code.
 *
 * <p>Sealed so the bridging policy stays exhaustive: {@link Level} maps to
 * Brigadier's {@code requires(source -> source.hasPermission(n))} on every cell;
 * {@code Node} (permission-plugin bridging with op-level fallback, probed never
 * hard-depended) lands with sub-06 Stage D and joins the {@code permits} clause
 * then.
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
}
