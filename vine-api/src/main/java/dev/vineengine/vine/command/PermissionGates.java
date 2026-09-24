package dev.vineengine.vine.command;

import java.util.function.BooleanSupplier;

/**
 * The node-permission precedence rule, in one place (sub-06 Stage D): engine
 * bridge first (a plugin owns node policy), then the cell's native provider when
 * it has an opinion, then the descriptor's fallback op level.
 *
 * <p>Pure Java by construction — no Brigadier, no loader types — so the
 * precedence is provable without a running game: the drivers adapt native
 * sources into the three inputs below, and the TCK's {@code perm tck_policy}
 * probe exercises the matrix in-process. Engine plumbing rather than a consumer
 * extension surface (consumers install a {@link VinePermissionBridge}).
 */
public final class PermissionGates {

    private PermissionGates() {
    }

    /**
     * Decides one node gate.
     *
     * @param gate the descriptor's gate
     * @param bridge the installed engine bridge, or {@code null}
     * @param nativeVerdict the cell provider's opinion, or {@code null} for "none"
     * @param fallback the descriptor's fallback op-level check
     */
    public static boolean decide(VinePermission.Node gate, VinePermissionBridge bridge,
                                 CommandSourceRef source,
                                 Boolean nativeVerdict, BooleanSupplier fallback) {
        if (bridge != null) {
            return bridge.has(source, gate.node(), gate.fallbackLevel());
        }
        if (nativeVerdict != null) {
            return nativeVerdict;
        }
        return fallback.getAsBoolean();
    }
}
