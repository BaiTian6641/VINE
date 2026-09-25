package dev.vineengine.vine.internal.driver1211.fabric.tck;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;

import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer;
import dev.vineengine.vine.registry.VineId;

/**
 * Driver-owned GameTest proof for the Fabric cell's use path (sub-07 Stage C): it
 * places the testmod's {@code vine_test:counterblock} — the exemplar that declares a
 * use behavior — and drives a real block interaction at it, then reads what the
 * behavior printed.
 *
 * <p><b>Why the printed line is the evidence:</b> the behavior is the engine's own
 * consumer-side code ({@code CounterExemplar.CounterBehavior}), and what it prints is
 * the context the engine handed it — position, block, state, player, hand, face and
 * hit point. Asserting that line is asserting the engine view the cell built, which is
 * exactly the claim "a cell wires the native interaction into the engine dispatch and
 * hands it an engine-only context" that no result code alone can make.
 *
 * <p><b>Why this trigger:</b> {@code TestContext#useBlock} is the game's own
 * interaction driver — it calls the block's {@code onUseWithItem} first and then
 * {@code onUse}, the same order {@code ServerPlayerInteractionManager#interactBlock}
 * uses for a player's right click, so the hook this test exercises is the one a live
 * player reaches. The face and the hit point are chosen rather than defaulted, so the
 * assertion pins the mapping instead of a coincidence of vanilla's defaults.
 */
public final class BehaviorUseGameTest implements FabricGameTest {

    /** The testmod's counter block: engine storage plus a use behavior. */
    private static final VineId COUNTER_BLOCK_ID = VineId.of("vine_test", "counterblock");

    /** Drives one native interaction and asserts the engine's use line. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "vine_behavior_use")
    public void useBehaviorFiresWithNativeContext(TestContext context) {
        Block counterBlock = FabricContentMaterializer.blockFor(COUNTER_BLOCK_ID);
        context.assertTrue(counterBlock != null, COUNTER_BLOCK_ID + " must be materialized — the testmod rides this"
            + " cell's dev-run classpath");

        BlockPos relative = new BlockPos(1, 1, 1);
        context.setBlockState(relative, counterBlock);
        PlayerEntity player = context.createMockPlayer(GameMode.CREATIVE);
        BlockPos absolute = context.getAbsolutePos(relative);
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(absolute), Direction.NORTH, absolute, false);

        String printed = interactAndCapture(context, relative, player, hit);
        String expectedPosition = absolute.getX() + "," + absolute.getY() + "," + absolute.getZ();
        // One printed line per observation, so the run log carries the evidence the
        // assertions below read (the same convention the driver's hook test follows).
        System.out.println("vine-driver-behavior: use " + printed.trim());

        context.assertTrue(printed.contains("tck: use pos=" + expectedPosition + " block=vine_test:counterblock"),
            "the engine use line must name the interacted block and its engine position, got: " + printed);
        context.assertTrue(printed.contains("player=" + player.getName().getString()),
            "the use context must carry the interacting player, got: " + printed);
        context.assertTrue(printed.contains("hand=MAIN"),
            "the use context must carry the hand that acted, got: " + printed);
        context.assertTrue(printed.contains("face=NORTH"),
            "the use context must carry the clicked face, got: " + printed);
        context.assertTrue(printed.contains("hit=" + String.format(Locale.ROOT, "%.3f,%.3f,%.3f",
                absolute.getX() + 0.5, absolute.getY() + 0.5, absolute.getZ() + 0.5)),
            "the use context must carry the hit point, got: " + printed);
        context.complete();
    }

    /**
     * Runs one native interaction at {@code pos} and returns everything the run wrote
     * to the JVM's standard out — the surface the exemplar behavior reports on, and the
     * one this test can read without reaching into the engine.
     */
    private static String interactAndCapture(TestContext context, BlockPos pos, PlayerEntity player,
            BlockHitResult hit) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            context.useBlock(pos, player, hit);
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }
}
