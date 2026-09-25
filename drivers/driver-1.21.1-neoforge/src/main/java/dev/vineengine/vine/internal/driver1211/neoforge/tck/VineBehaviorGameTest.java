package dev.vineengine.vine.internal.driver1211.neoforge.tck;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeContentMaterializer;
import dev.vineengine.vine.registry.VineId;

/**
 * Driver-owned GameTest proof for the NeoForge cell's use path (sub-07 Stage C): it
 * places the testmod's {@code vine_test:counterblock} — the exemplar that declares a
 * use behavior — and drives a real block interaction at it through NF's own
 * interaction helper, then reads what the behavior printed.
 *
 * <p><b>Why the printed line is the evidence:</b> the behavior is the engine's own
 * consumer-side code ({@code CounterExemplar.CounterBehavior}), and what it prints is
 * the context the engine handed it — position, block, state, player, hand, face and
 * hit point. Asserting that line is asserting the engine view the cell built, which is
 * exactly the claim "a cell wires the native interaction into the engine dispatch and
 * hands it an engine-only context" that no result code alone can make.
 *
 * <p><b>Why this trigger:</b> {@code GameTestHelper#useBlock} is the game's own
 * interaction driver — it calls {@code BlockState#useItemOn}, the layer a live player's
 * right-click reaches from {@code ServerPlayerGameMode} too — so the hook this test
 * exercises is the one a real interaction reaches, and it is reached without calling
 * the engine's dispatch directly. The hit result is built at the absolute position with
 * a chosen face, so the assertion pins the mapping instead of a coincidence of the
 * helper's defaults (which do not rebase a caller-supplied hit result).
 */
@GameTestHolder("vine")
@PrefixGameTestTemplate(false)
public final class VineBehaviorGameTest {

    /** The testmod's counter block: engine storage plus a use behavior. */
    private static final VineId COUNTER_BLOCK_ID = VineId.of("vine_test", "counterblock");

    private VineBehaviorGameTest() {
    }

    /** Drives one native interaction and asserts the engine's use line. */
    @GameTest(template = "empty", batch = "vine_behavior_use")
    public static void useBehaviorFiresWithNativeContext(GameTestHelper helper) {
        Block counterBlock = NeoForgeContentMaterializer.blockFor(COUNTER_BLOCK_ID);
        helper.assertTrue(counterBlock != null, COUNTER_BLOCK_ID + " must be materialized — the testmod rides this"
            + " cell's dev-run classpath");

        BlockPos relative = new BlockPos(1, 1, 1);
        helper.setBlock(relative, counterBlock);
        Player player = helper.makeMockPlayer(GameType.CREATIVE);
        BlockPos absolute = helper.absolutePos(relative);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.NORTH, absolute, false);

        String printed = interactAndCapture(helper, relative, player, hit);
        String expectedPosition = absolute.getX() + "," + absolute.getY() + "," + absolute.getZ();
        // One printed line per observation, so the run log carries the evidence the
        // assertions below read (the same convention the driver's TCK batch follows).
        System.out.println("vine-driver-behavior: use " + printed.trim());

        helper.assertTrue(printed.contains("tck: use pos=" + expectedPosition + " block=vine_test:counterblock"),
            "the engine use line must name the interacted block and its engine position, got: " + printed);
        helper.assertTrue(printed.contains("player=" + player.getName().getString()),
            "the use context must carry the interacting player, got: " + printed);
        helper.assertTrue(printed.contains("hand=MAIN"),
            "the use context must carry the hand that acted, got: " + printed);
        helper.assertTrue(printed.contains("face=NORTH"),
            "the use context must carry the clicked face, got: " + printed);
        helper.assertTrue(printed.contains("hit=" + String.format(Locale.ROOT, "%.3f,%.3f,%.3f",
                absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5)),
            "the use context must carry the hit point, got: " + printed);
        helper.succeed();
    }

    /**
     * Runs one native interaction at {@code pos} and returns everything the run wrote
     * to the JVM's standard out — the surface the exemplar behavior reports on, and the
     * one this test can read without reaching into the engine.
     */
    private static String interactAndCapture(GameTestHelper helper, BlockPos pos, Player player, BlockHitResult hit) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            helper.useBlock(pos, player, hit);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
