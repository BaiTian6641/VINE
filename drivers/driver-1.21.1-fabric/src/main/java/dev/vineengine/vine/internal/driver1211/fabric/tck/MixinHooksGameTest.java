package dev.vineengine.vine.internal.driver1211.fabric.tck;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;

import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.EventPriority;
import dev.vineengine.vine.Subscription;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.hook.HookEvents;

/**
 * Driver-owned GameTest smoke for the two Mixin-backed hook slots (sub-18 Stage
 * E). The testmod's hook path cannot trigger them (it posts synthetic command
 * events and subscribes to loader-API slots only), so this driver test is the
 * source of the stage's evidence: it subscribes to {@code blockPlace} and
 * {@code worldSave}, drives vanilla's own placement and save paths, and asserts
 * the normalized payloads plus the veto/guard semantics against the world.
 *
 * <p>Why these triggers:
 * <ul>
 *   <li>placement — {@code createMockPlayer} + {@code useStackOnBlock}, i.e.
 *   vanilla's {@code ItemStack#useOnBlock} with a real placement context, the
 *   same path a player's right click takes (a dispenser is covered by the same
 *   seam but cannot be asserted this cheaply);</li>
 *   <li>save — {@code ServerWorld#save} with the flag pairs, the method the
 *   {@code ServerWorldSaveMixin} injects into and the one {@code
 *   MinecraftServer#save} calls for every world.</li>
 * </ul>
 * The optional/tall cases are not decoration: they pin the two claims the Mixins
 * rest on — the seam fires exactly once per placement even when the block is a
 * two-high door whose {@code place} override calls {@code super}, and
 * {@code skipSave=true} stays silent. One printed line per observation keeps the
 * evidence readable in the {@code runGametest} log.
 */
public final class MixinHooksGameTest implements FabricGameTest {

    /** Drives both seams and asserts payload, veto and guard behavior. */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, batchId = "vine_mixin_hooks")
    public void mixinBackedHooksFire(TestContext context) {
        EventBus bus = VineEngine.get().events();
        ServerWorld world = context.getWorld();
        String worldId = world.getRegistryKey().getValue().toString();
        List<HookEvents.BlockPlace> places = new ArrayList<>();
        List<String> vetoes = new ArrayList<>();
        List<String> saves = new ArrayList<>();

        Subscription placeObserver = bus.subscribe(HookEvents.BlockPlace.class, event -> {
            places.add(event);
            System.out.println("vine-driver-mixin: blockPlace world=" + event.worldId()
                + " block=" + event.blockId()
                + " pos=" + event.x() + "," + event.y() + "," + event.z()
                + " player=" + event.playerUuid());
        });
        // FIRST, so the veto gates the NORMAL observer and the vanilla write.
        Subscription placeVeto = bus.subscribe(HookEvents.BlockPlace.class, EventPriority.FIRST, event -> {
            if ("minecraft:dirt".equals(event.blockId())) {
                event.cancel();
                vetoes.add(event.blockId());
                System.out.println("vine-driver-mixin: blockPlace vetoed block=" + event.blockId());
            }
        });
        Subscription saveObserver = bus.subscribe(HookEvents.WorldSave.class, event -> {
            saves.add(event.worldId());
            System.out.println("vine-driver-mixin: worldSave world=" + event.worldId());
        });
        try {
            PlayerEntity player = context.createMockPlayer(GameMode.CREATIVE);
            System.out.println("vine-driver-mixin: hooked slots installed=" + bus.activeHookInstalls());

            BlockPos stoneSupport = new BlockPos(1, 1, 1);
            context.setBlockState(stoneSupport, Blocks.STONE.getDefaultState());
            BlockPos stonePlaced = context.getAbsolutePos(stoneSupport.offset(Direction.UP));
            ItemStack stone = new ItemStack(Items.STONE);
            player.setStackInHand(Hand.MAIN_HAND, stone);
            context.useStackOnBlock(player, stone, stoneSupport, Direction.UP);

            context.assertTrue(places.size() == 1, "one BlockPlace per placement, got " + places.size());
            HookEvents.BlockPlace placed = places.get(0);
            context.assertEquals(worldId, placed.worldId(), "placed-in world id");
            context.assertEquals("minecraft:stone", placed.blockId(), "placed block id");
            context.assertEquals(stonePlaced, new BlockPos(placed.x(), placed.y(), placed.z()),
                "placed position");
            context.assertEquals(player.getUuidAsString(), placed.playerUuid(), "placing player");
            context.assertTrue(world.getBlockState(stonePlaced).isOf(Blocks.STONE),
                "an unvetoed placement still lands");

            // Two-high door: BlockItem's place override calls super, so a Mixin on
            // that override (or on BlockItem's) would report twice; the public seam
            // must report exactly once.
            BlockPos doorSupport = new BlockPos(1, 1, 3);
            context.setBlockState(doorSupport, Blocks.GRASS_BLOCK.getDefaultState());
            ItemStack door = new ItemStack(Items.OAK_DOOR);
            player.setStackInHand(Hand.MAIN_HAND, door);
            context.useStackOnBlock(player, door, doorSupport, Direction.UP);
            context.assertTrue(places.size() == 2, "two-high placement reports once, got " + places.size());

            BlockPos dirtSupport = new BlockPos(3, 1, 1);
            context.setBlockState(dirtSupport, Blocks.STONE.getDefaultState());
            BlockPos dirtPlaced = context.getAbsolutePos(dirtSupport.offset(Direction.UP));
            ItemStack dirt = new ItemStack(Items.DIRT);
            player.setStackInHand(Hand.MAIN_HAND, dirt);
            context.useStackOnBlock(player, dirt, dirtSupport, Direction.UP);

            context.assertTrue(vetoes.size() == 1, "veto handler saw the dirt placement");
            context.assertTrue(places.size() == 2, "a vetoed placement gates later handlers");
            context.assertTrue(world.getBlockState(dirtPlaced).isAir(),
                "a vetoed placement is never applied");
            context.assertTrue(dirt.getCount() == 1, "a vetoed placement consumes no item");

            world.save(null, false, true);
            context.assertTrue(saves.isEmpty(), "skipSave=true must not report a save");
            world.save(null, false, false);
            context.assertTrue(saves.size() == 1, "one WorldSave per saved world, got " + saves.size());
            context.assertEquals(worldId, saves.get(0), "saved world id");

            System.out.println("vine-driver-mixin: mixin hooks ok places=" + places.size()
                + " vetoes=" + vetoes.size() + " saves=" + saves.size());
        } finally {
            placeObserver.close();
            placeVeto.close();
            saveObserver.close();
        }
        context.complete();
    }
}
