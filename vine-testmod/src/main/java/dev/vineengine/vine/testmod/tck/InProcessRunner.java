package dev.vineengine.vine.testmod.tck;

import java.util.List;

import dev.vineengine.vine.command.VineCommand;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.internal.tck.InProcessScenarios;
import dev.vineengine.vine.registry.VineId;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * The testmod's in-process TCK entry (sub-21 Stage B): {@code /vine_test tck_run
 * <id|all>} delegates to the engine-side interpreter, so the DSL has exactly one
 * implementation and every harness that drives it sees the same verdicts.
 */
public final class InProcessRunner {

    private InProcessRunner() {
    }

    /** Registers {@code /vine_test tck_run <id|all>}. */
    public static void register() {
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("tck_run")
                .then(VineCommand.argument("target", dev.vineengine.vine.command.VineArgumentTypes.STRING)
                    .executes(ctx -> {
                        List<String> verdicts = InProcessScenarios.run(ctx.argument("target", String.class));
                        for (String verdict : verdicts) {
                            ctx.feedback(verdict);
                            // Also on stdout: a nested in-process run (a scenario
                            // driving the harness) must see the verdict too.
                            System.out.println(verdict);
                        }
                        return verdicts.stream().noneMatch(v -> v.contains(": FAIL")) ? 1 : 0;
                    })))
            .build(VineId.of(MOD_ID, "tck_run")));
    }
}
