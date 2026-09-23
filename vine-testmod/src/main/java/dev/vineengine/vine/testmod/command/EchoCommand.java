package dev.vineengine.vine.testmod.command;

import dev.vineengine.vine.command.VineArgumentTypes;
import dev.vineengine.vine.command.VineCommand;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.registry.VineId;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * Canonical command exemplar (sub-06, M0 minimal): {@code /vine_test echo <msg>}
 * replies {@code "echo: <msg>"} to the source. The descriptor's VineId lives in
 * the {@code vinetest} namespace per the engine-wide namespace ruling; the
 * command literal keeps the {@code vine_test} mod id (sub-06 pins the command
 * name).
 *
 * <p>Why this shape: one literal root, one child literal, one string argument
 * with an executor is the smallest tree that exercises the full Stage A
 * descriptor subset (literal, argument, permission gate, executes) — the same
 * shape a real consumer's first debug command takes.
 *
 * <p>Registration runs from consumer init while REGISTRIES_OPEN is in effect;
 * the engine rejects registrations after REGISTRIES_FROZEN, so no phase
 * anchoring is needed. The TCK command scenario runs
 * {@code vine_test echo hello} and asserts the {@code "echo: hello"} feedback.
 */
public final class EchoCommand {

    private EchoCommand() {
    }

    /** Registers the echo command descriptor with the engine. */
    public static void register() {
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("echo")
                .then(VineCommand.argument("msg", VineArgumentTypes.STRING)
                    .executes(ctx -> {
                        ctx.feedback("echo: " + ctx.argument("msg", String.class));
                        return 1;
                    })))
            .build(VineId.of("vinetest", MOD_ID)));
    }
}
