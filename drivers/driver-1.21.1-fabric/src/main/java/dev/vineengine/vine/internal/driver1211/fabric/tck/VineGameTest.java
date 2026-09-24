package dev.vineengine.vine.internal.driver1211.fabric.tck;

import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

import dev.vineengine.vine.internal.ConsoleDispatch;
import dev.vineengine.vine.internal.tck.InProcessScenarios;

/**
 * The Fabric half of the TCK's GameTest harness path (sub-21 Stage B): a batch
 * that runs the *same* scenario files through the *same* interpreter
 * ({@code InProcessRunner}) the {@code /vinetck run} command uses, so a scenario's
 * meaning never depends on which harness executed it.
 *
 * <p>The batch is deliberately thin. World-, packet- and restart-shaped steps are
 * the external runner's job and the interpreter reports them as {@code SKIP} with
 * that reason; what GameTest adds here is the loader's own test lifecycle
 * (structure spawning, tick budget, per-test isolation) around scenarios that are
 * command-shaped.
 */
public final class VineGameTest implements FabricGameTest {

    /** Console dispatch for the in-process interpreter — this cell owns the source. */
    private static void bindConsole(TestContext context) {
        ConsoleDispatch.install((command, sink) -> context.getWorld().getServer().getCommandManager()
            .executeWithPrefix(context.getWorld().getServer().getCommandSource(), command));
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "vine_tck")
    public void scenariosRunInProcess(TestContext context) {
        bindConsole(context);
        List<String> verdicts = InProcessScenarios.run("harness_probe");
        for (String verdict : verdicts) {
            context.getWorld().getServer().sendMessage(
                net.minecraft.text.Text.literal("[VINE gametest] " + verdict));
        }
        for (String verdict : verdicts) {
            if (verdict.contains(": FAIL")) {
                context.throwGameTestException("scenario failed in-process: " + verdict);
            }
        }
        context.complete();
    }
}
