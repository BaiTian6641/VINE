package dev.vineengine.vine.testmod.command;

import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.registry.VineId;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * JSON command exemplar (sub-06 Stage B): registers the behavior that
 * {@code data/vine_test/vine/commands/quest.json} references by id. A JSON node
 * cannot carry a lambda, so executors are the seam — and resolution happens at
 * load time, which makes a typo a load failure instead of a command that parses
 * and does nothing.
 *
 * <p>The file proves the data path end to end: op-level permission, enum
 * argument with data-declared constants, literal suggestion list, a redirect
 * alias into another consumer's tree, and executor references — all parsed by
 * the engine's own compiler, so the merge policy and dispatcher walker treat it
 * exactly like a Java descriptor.
 */
public final class CommandJsonExemplar {

    private CommandJsonExemplar() {
    }

    /** Registers the executors the JSON descriptor references. */
    public static void register() {
        VineCommands commands = VineCommands.get();
        commands.registerExecutor(VineId.of(MOD_ID, "json_start"), ctx -> {
            ctx.feedback("quest json: started");
            return 1;
        });
        commands.registerExecutor(VineId.of(MOD_ID, "json_mode"), ctx -> {
            ctx.feedback("quest json: mode=" + ctx.argument("mode", String.class));
            return 1;
        });
        commands.registerExecutor(VineId.of(MOD_ID, "json_status"), ctx -> {
            ctx.feedback("quest json: status ok");
            return 1;
        });
    }
}
