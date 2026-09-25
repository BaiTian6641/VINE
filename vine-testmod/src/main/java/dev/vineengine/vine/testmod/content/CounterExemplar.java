package dev.vineengine.vine.testmod.content;

import java.util.List;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.BlockEntityDescriptor;
import dev.vineengine.vine.content.BlockTickContext;
import dev.vineengine.vine.content.BlockTuning;
import dev.vineengine.vine.content.BlockUseContext;
import dev.vineengine.vine.content.LootBehavior;
import dev.vineengine.vine.content.LootContext;
import dev.vineengine.vine.content.LootSink;
import dev.vineengine.vine.content.ModelHint;
import dev.vineengine.vine.content.TickBehavior;
import dev.vineengine.vine.content.UseBehavior;
import dev.vineengine.vine.content.UseResult;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.VineWorlds;

/**
 * The Stage C exemplars (sub-07): one block that carries both a tick behavior and a
 * use behavior over an engine-stored counter.
 *
 * <p>Why this shape proves the stage: the counter lives in the holder's
 * {@code VoxelData} tree — not in the behavior, not in a static, not in the
 * block entity class — so the number a scenario reads after a save/reload is
 * persisted state that only the behavior could have written, and the number it reads
 * after a fixed number of ticks is the tick dispatch's own count. The behavior is
 * stateless, which is the contract every behavior carries.
 *
 * <p>Both families are declared on one descriptor on purpose: a cell's "install only
 * what is declared" logic has to see a block that declares two families and blocks
 * that declare none (the other testmod blocks) in the same run, or the Minimal
 * Footprint assertion would be vacuous.
 */
public final class CounterExemplar {

    /** The counter block: engine storage (ticking) plus behaviors, no state model. */
    public static final VineId COUNTER_BLOCK_ID = VineId.of("vine_test", "counterblock");

    /** The counter's schema: one integer, {@code count}. */
    public static final VineId COUNTER_SCHEMA_ID = VineId.of("vine_test", "counter");

    /** The tree path the behavior increments. */
    public static final String COUNT_PATH = "count";

    /** Server ticks between counter ticks — the interval the scenario's arithmetic uses. */
    public static final int TICK_INTERVAL = 20;

    /** The item a loot behavior contributes, so a scenario can name one id. */
    public static final VineId LOOT_ITEM_ID = VineId.of("vine_test", "testitem");

    /** Placeholder payload codec, exactly like the testmod's other schemas. */
    private static final Codec<VoxelData> CODEC = Codec.unit(null);

    private CounterExemplar() {
    }

    /** Registers the counter's schema, block and behaviors. */
    public static void register() {
        VineData.registerSchema(new VoxelSchema(COUNTER_SCHEMA_ID, 1, CODEC), List.of());
        VineRegistries.register(VineContent.BLOCK_TYPE, COUNTER_BLOCK_ID,
            new BlockDescriptor(COUNTER_BLOCK_ID, List.of(), BlockTuning.STONE_LIKE,
                BlockEntityDescriptor.ticking(COUNTER_SCHEMA_ID, TICK_INTERVAL),
                List.of(new CounterBehavior(), new LootExemplarBehavior()), ModelHint.cubeAll()));
    }

    /**
     * Reads the counter at {@code pos} through the engine world view — the same path
     * a consumer has — and prints it for the scenario to assert.
     */
    public static void read(int x, int y, int z) {
        BlockPos pos = BlockPos.of(x, y, z);
        var tree = VineWorlds.overworld().dataAt(pos);
        if (tree.isEmpty()) {
            System.out.println("tck: counter @" + pos.asString() + " absent");
            return;
        }
        VoxelData data = tree.get();
        int count = data.contains(COUNT_PATH) ? data.getInt(COUNT_PATH) : -1;
        System.out.println("tck: counter @" + pos.asString() + " count=" + count);
    }

    /**
     * The engine's loot modifier: adds one {@link #LOOT_ITEM_ID} on every removal and
     * prints the context it was given. It is a modifier, not a replacement — the
     * block's own drops (there are none here) are the cell's business, and this only
     * contributes.
     */
    public static final class LootExemplarBehavior implements LootBehavior {

        @Override
        public void modifyLoot(LootContext ctx, LootSink out) {
            // The context is the contract again: state is printed because the engine
            // view must carry it, and miner is printed as an explicit "none" when the
            // removal had no player (a /setblock destroy, an explosion, a piston) —
            // a cell that invented an actor here would be lying.
            System.out.println("tck: loot pos=" + ctx.pos().asString() + " block=" + ctx.state().blockId()
                + " state=" + ctx.state().fragment() + " miner="
                + ctx.miner().map(miner -> miner.name()).orElse("none")
                + " added=" + LOOT_ITEM_ID);
            out.add(LOOT_ITEM_ID, 1);
        }
    }

    /**
     * The engine's counter: increments the holder's own tree by one per tick
     * dispatch. Stateless — the number lives in the tree, so a fresh behavior instance
     * on the next boot continues the same count.
     */
    public static final class CounterBehavior implements TickBehavior, UseBehavior {

        @Override
        public void tick(BlockTickContext ctx) {
            VoxelData data = ctx.data().orElseThrow(() -> new IllegalStateException(
                "counter block ticked without a holder tree — the block declares a block entity, so a tick without"
                    + " data means the cell did not open it"));
            int next = (data.contains(COUNT_PATH) ? data.getInt(COUNT_PATH) : 0) + 1;
            data.put(COUNT_PATH, next);
        }

        @Override
        public UseResult onUse(BlockUseContext ctx) {
            // The context is the contract: printing all of it is how a scenario (and a
            // cell's GameTest) checks that the engine view carries what it promises.
            System.out.println("tck: use pos=" + ctx.pos().asString() + " block=" + ctx.state().blockId()
                + " state=" + ctx.state().fragment() + " player=" + ctx.player().name()
                + " hand=" + ctx.hand() + " face=" + ctx.face() + " hit=" + ctx.hit().asString());
            return UseResult.HANDLED;
        }
    }
}
