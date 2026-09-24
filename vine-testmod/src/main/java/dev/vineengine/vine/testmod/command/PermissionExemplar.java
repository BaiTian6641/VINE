package dev.vineengine.vine.testmod.command;

import java.util.concurrent.atomic.AtomicInteger;

import dev.vineengine.vine.command.CommandSourceRef;
import dev.vineengine.vine.command.VineCommand;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.command.VinePermissionBridge;
import dev.vineengine.vine.registry.VineId;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * Permission-bridging exemplar (sub-06 Stage D): a node-gated command tree plus
 * the bridge the TCK installs to prove both halves of the policy.
 *
 * <p>{@code /vine_test perm grant} and {@code /vine_test perm deny} carry
 * {@link VinePermission.Node} gates for the same node names the bridge answers;
 * {@code /vine_test perm fallback} carries a node no bridge claims, so it
 * resolves through the descriptor's fallback op level. The bridge records every
 * decision, and {@code /vine_test perm stats} reads that record back — which is
 * how the TCK observes a *denial* (a denied source is filtered out of the
 * Brigadier tree, so the denial itself has no console output to assert).
 */
public final class PermissionExemplar {

    /** The node the bridge grants. */
    public static final String GRANTED_NODE = "vine_test.perm.grant";
    /** The node the bridge denies. */
    public static final String DENIED_NODE = "vine_test.perm.deny";
    /** The node no provider claims: fallback level 2 (console op-4 passes). */
    public static final VinePermission.Node FALLBACK_GATE = VinePermission.node("vine_test.perm.unclaimed", 2);

    private static final AtomicInteger CALLS = new AtomicInteger();
    private static volatile String lastNode = "none";
    private static volatile Boolean lastVerdict;

    private PermissionExemplar() {
    }

    /** Registers the node-gated tree. */
    public static void register() {
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("perm")
                .then(VineCommand.literal("grant")
                    .permission(VinePermission.node(GRANTED_NODE, 2))
                    .executes(ctx -> {
                        ctx.feedback("perm grant ok");
                        return 1;
                    }))
                .then(VineCommand.literal("deny")
                    .permission(VinePermission.node(DENIED_NODE, 2))
                    .executes(ctx -> {
                        ctx.feedback("perm deny ok (BUG: gate did not deny)");
                        return 1;
                    }))
                .then(VineCommand.literal("fallback")
                    .permission(FALLBACK_GATE)
                    .executes(ctx -> {
                        ctx.feedback("perm fallback ok");
                        return 1;
                    }))
                .then(VineCommand.literal("stats")
                    .executes(ctx -> {
                        ctx.feedback("perm bridge: calls=" + CALLS.get() + " last=" + lastNode
                            + " verdict=" + lastVerdict);
                        return 1;
                    }))
                .then(VineCommand.literal("tck_policy")
                    .executes(ctx -> {
                        // The precedence matrix, exercised in-process (sub-06 Stage
                        // D): bridge beats native beats fallback. The console is
                        // always op-4 headless, so the fallback *denial* half can
                        // only be observed here — with a fallback checker that
                        // says no, exactly as a lower-op source would see.
                        ctx.feedback("policy: " + policyMatrix());
                        return 1;
                    }))
                .then(VineCommand.literal("tck_bridge_install")
                    .executes(ctx -> {
                        CALLS.set(0);
                        lastNode = "none";
                        lastVerdict = null;
                        VineCommands.get().registerPermissionBridge(PermissionExemplar::decide);
                        ctx.feedback("perm bridge installed");
                        return 1;
                    }))
                .then(VineCommand.literal("tck_bridge_clear")
                    .executes(ctx -> {
                        VineCommands.get().registerPermissionBridge(null);
                        ctx.feedback("perm bridge cleared");
                        return 1;
                    })))
            .build(VineId.of(MOD_ID, "permissions")));
    }

    /** Runs the precedence matrix and renders it as one line (TCK assertion target). */
    private static String policyMatrix() {
        CommandSourceRef fake = new CommandSourceRef() {
            @Override
            public String name() {
                return "policy-probe";
            }

            @Override
            public boolean isPlayer() {
                return false;
            }

            @Override
            public java.util.Optional<dev.vineengine.vine.VinePlayer> player() {
                return java.util.Optional.empty();
            }
        };
        VinePermission.Node gate = VinePermission.node("vine_test.perm.probe", 2);
        boolean bridgeWins = dev.vineengine.vine.command.PermissionGates.decide(gate,
            (source, node, fallbackLevel) -> true, fake, Boolean.FALSE, () -> false);
        boolean nativeWins = dev.vineengine.vine.command.PermissionGates.decide(gate,
            null, fake, Boolean.TRUE, () -> false);
        boolean nativeDenies = dev.vineengine.vine.command.PermissionGates.decide(gate,
            null, fake, Boolean.FALSE, () -> true);
        boolean fallbackAllowed = dev.vineengine.vine.command.PermissionGates.decide(gate,
            null, fake, null, () -> true);
        boolean fallbackDenied = dev.vineengine.vine.command.PermissionGates.decide(gate,
            null, fake, null, () -> false);
        return "bridge=" + bridgeWins + " nativeAllow=" + nativeWins + " nativeDeny=" + nativeDenies
            + " fallbackAllow=" + fallbackAllowed + " fallbackDeny=" + fallbackDenied;
    }

    /** The exemplar provider: grants one node, denies another, ignores the rest. */
    private static boolean decide(CommandSourceRef source, String node, int fallbackLevel) {
        CALLS.incrementAndGet();
        lastNode = node;
        // A real provider layers defaults from the fallback level for nodes it
        // does not own; the exemplar keeps the unclaimed node unowned instead.
        lastVerdict = switch (node) {
            case GRANTED_NODE -> true;
            case DENIED_NODE -> false;
            default -> source.name() != null && fallbackLevel <= 4;
        };
        return lastVerdict;
    }
}
