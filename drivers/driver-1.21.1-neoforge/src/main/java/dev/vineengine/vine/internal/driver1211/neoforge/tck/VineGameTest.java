package dev.vineengine.vine.internal.driver1211.neoforge.tck;

import java.util.List;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import dev.vineengine.vine.internal.ConsoleDispatch;
import dev.vineengine.vine.internal.tck.InProcessScenarios;

/**
 * The NeoForge half of the TCK's GameTest harness path (sub-21 Stage B): a batch
 * that runs the *same* scenario files through the *same* interpreter the
 * {@code /vine_test tck_run} command and the Fabric gametest use, so a scenario's
 * meaning never depends on which harness executed it.
 *
 * <p>Two NeoForge-specific details, both from its GameTest documentation
 * (docs.neoforged.net/docs/1.21.1/misc/gametest):
 * <ul>
 *   <li>a template's file name is the class name lowercased plus the template
 *       name unless {@code @PrefixGameTestTemplate(false)} turns the prefix off —
 *       this cell turns it off, so the shipped asset is plainly
 *       {@code data/vine/structure/empty.nbt};</li>
 *   <li>the template must be a real structure NBT (a 1.21 structure: DataVersion,
 *       size and a flattened {@code data} list of positions and states) placed
 *       under {@code data/<namespace>/structure/} — singular — not the vanilla
 *       gametest's bundled empty template, which dev runs do not ship.</li>
 * </ul>
 */
@GameTestHolder("vine")
public final class VineGameTest {

    @PrefixGameTestTemplate(false)
    @GameTest(template = "empty", batch = "vine_tck")
    public static void scenariosRunInProcess(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        ConsoleDispatch.install((command, sink) -> server.getCommands()
            .performPrefixedCommand(server.createCommandSourceStack(), command));

        List<String> verdicts = InProcessScenarios.run("harness_probe");
        for (String verdict : verdicts) {
            if (verdict.contains(": FAIL")) {
                throw new AssertionError("scenario failed in-process: " + verdict);
            }
        }
        helper.succeed();
    }
}
