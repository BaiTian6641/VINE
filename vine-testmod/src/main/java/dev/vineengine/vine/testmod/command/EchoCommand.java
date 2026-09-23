package dev.vineengine.vine.testmod.command;

import dev.vineengine.vine.command.VineArgumentTypes;
import dev.vineengine.vine.command.VineCommand;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.testmod.net.EchoPacket;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * Canonical command exemplar (sub-06, M0 minimal): {@code /vine_test echo <msg>}
 * replies {@code "echo: <msg>"} to the source, plus the TCK drive child
 * {@code /vine_test tck_echo <number> <text>} that fires one echo payload into
 * the engine's C2S path (sub-21 Stage B — the scenario runner invokes it on a
 * headless server launched with {@code vine.tck.loopback}).
 *
 * <p>One root, one descriptor: the engine's merge policy is first-registered-
 * wins on root literals (sub-06 §4), so every {@code vine_test} child lives in
 * THIS tree — a second root-literal descriptor would be dropped as a conflict.
 */
public final class EchoCommand {

    private EchoCommand() {
    }

    /** Registers the vine_test command tree with the engine. */
    public static void register() {
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("echo")
                .then(VineCommand.argument("msg", VineArgumentTypes.STRING)
                    .executes(ctx -> {
                        ctx.feedback("echo: " + ctx.argument("msg", String.class));
                        return 1;
                    })))
            .then(VineCommand.literal("tck_echo")
                .then(VineCommand.argument("number", VineArgumentTypes.STRING)
                    .then(VineCommand.argument("text", VineArgumentTypes.STRING)
                        .executes(ctx -> {
                            int number = Integer.parseInt(ctx.argument("number", String.class));
                            String text = ctx.argument("text", String.class);
                            EchoPacket.sendToServer(new EchoPacket.Payload(number, text));
                            ctx.feedback("tck: sent echo number=" + number + " text=" + text);
                            return 1;
                        }))))
            .then(VineCommand.literal("tck_caps")
                .executes(ctx -> {
                    dev.vineengine.vine.testmod.capability.CapabilityExemplar.runProof();
                    return 1;
                }))
            .build(VineId.of("vinetest", MOD_ID)));
    }
}
