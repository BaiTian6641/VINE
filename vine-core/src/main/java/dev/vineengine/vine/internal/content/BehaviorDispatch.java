package dev.vineengine.vine.internal.content;

import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.content.BlockBehavior;
import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.BlockTickContext;
import dev.vineengine.vine.content.BlockUseContext;
import dev.vineengine.vine.content.LootBehavior;
import dev.vineengine.vine.content.LootContext;
import dev.vineengine.vine.content.LootSink;
import dev.vineengine.vine.content.TickBehavior;
import dev.vineengine.vine.content.UseBehavior;
import dev.vineengine.vine.content.UseResult;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * The engine's block-behavior dispatch (sub-07 Stage C): the one place a descriptor's
 * ordered behavior list becomes calls. Cells never walk the list themselves — they
 * ask {@link #plan} what to wire for a block and then call the matching entry point
 * when their native signal fires, so "ordered", "first non-PASS wins" and "dormant
 * when absent" are engine rules rather than per-cell conventions.
 *
 * <p><b>Dormant by construction:</b> {@link #plan} reports an empty plan for a block
 * with no behaviors, and a cell that follows it installs no hook, no ticker and no
 * loot modifier — the Minimal Footprint rule this stage's boot assertion counts.
 *
 * <p><b>Threading:</b> dispatch runs on whatever thread the cell's native signal
 * arrives on, which for block interactions, ticks and loot is the server thread. The
 * engine adds no locking: behavior instances are shared, so they must be
 * thread-safe by the {@link BlockBehavior} contract.
 */
public final class BehaviorDispatch {

    /**
     * Blocks whose descriptor declares a ticking block entity, counted as they are
     * registered (the engine is the only place that sees every descriptor, so the
     * count is engine-side and a cell's own installed-ticker count must equal it).
     */
    private static final AtomicInteger TICKING_BLOCKS = new AtomicInteger();

    private BehaviorDispatch() {
    }

    /**
     * Notes one registered block descriptor — called by the engine at registration,
     * which is also where a descriptor's state model is validated. A cell never calls
     * this; it exists so {@link #tickingBlockCount()} can answer without walking a
     * registry that has no public iteration.
     */
    public static void noteRegistered(BlockDescriptor descriptor) {
        if (descriptor.blockEntity().map(be -> be.ticking()).orElse(false)) {
            TICKING_BLOCKS.incrementAndGet();
        }
    }

    /**
     * What a cell must wire for one block: which behavior families the block
     * declares, and whether its block entity ticks. A cell reads this once per
     * materialized block and installs exactly what it says — nothing more.
     *
     * @param use     whether the block declares at least one {@link UseBehavior}
     * @param tick    whether the block declares at least one {@link TickBehavior}
     * @param loot    whether the block declares at least one {@link LootBehavior}
     * @param ticking whether the block's block-entity declaration asks to tick
     * @param tickInterval the declared interval (in server ticks) when {@code ticking}
     */
    public record Plan(boolean use, boolean tick, boolean loot, boolean ticking, int tickInterval) {

        /** Whether this plan asks the cell to install anything at all. */
        public boolean installsAnything() {
            return use || tick || loot;
        }
    }

    /**
     * The plan for {@code blockId}.
     *
     * @throws IllegalStateException when the id is not a registered block descriptor —
     *         a cell asking about a block it never materialized is a broken boot
     *         invariant, reported rather than answered with an empty plan
     */
    public static Plan plan(VineId blockId) {
        BlockDescriptor descriptor = descriptorOf(blockId);
        List<BlockBehavior> behaviors = descriptor.behaviors();
        boolean ticking = descriptor.blockEntity().map(be -> be.ticking()).orElse(false);
        int interval = descriptor.blockEntity().map(be -> be.tickInterval()).orElse(1);
        return new Plan(
            has(behaviors, UseBehavior.class),
            has(behaviors, TickBehavior.class),
            has(behaviors, LootBehavior.class),
            ticking,
            interval);
    }

    /**
     * Runs {@code blockId}'s use behaviors in declared order and returns the first
     * outcome that is not {@link UseResult#PASS} (or {@code PASS} when the block has
     * none, which is the cell's cue to fall through to its own default handling).
     *
     * <p>A behavior that throws propagates: the cell's own error handling decides what
     * a throwing behavior means for the interaction, and the engine never swallows it.
     */
    public static UseResult use(VineId blockId, BlockUseContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        for (UseBehavior behavior : families(blockId, UseBehavior.class)) {
            UseResult result = behavior.onUse(ctx);
            if (result == null) {
                throw new IllegalStateException("use behavior " + behavior.getClass().getName() + " of " + blockId
                    + " returned null — a behavior must answer PASS, HANDLED or DENIED");
            }
            if (result != UseResult.PASS) {
                return result;
            }
        }
        return UseResult.PASS;
    }

    /** Runs {@code blockId}'s tick behaviors in declared order. */
    public static void tick(VineId blockId, BlockTickContext ctx) {
        Objects.requireNonNull(ctx, "ctx");
        for (TickBehavior behavior : families(blockId, TickBehavior.class)) {
            behavior.tick(ctx);
        }
    }

    /** Runs {@code blockId}'s loot behaviors in declared order, writing into {@code out}. */
    public static void loot(VineId blockId, LootContext ctx, LootSink out) {
        Objects.requireNonNull(ctx, "ctx");
        Objects.requireNonNull(out, "out");
        for (LootBehavior behavior : families(blockId, LootBehavior.class)) {
            behavior.modifyLoot(ctx, out);
        }
    }

    /**
     * How many registered blocks declare a ticking block entity — the engine-side
     * half of this stage's "blocks with no block entity install no ticker" assertion,
     * which a cell's own installed-ticker count must equal when printed at boot.
     */
    public static int tickingBlockCount() {
        return TICKING_BLOCKS.get();
    }

    private static <B extends BlockBehavior> boolean has(List<BlockBehavior> behaviors, Class<B> family) {
        for (BlockBehavior behavior : behaviors) {
            if (family.isInstance(behavior)) {
                return true;
            }
        }
        return false;
    }

    /** The descriptor's behaviors that belong to {@code family}, in declared order. */
    private static <B extends BlockBehavior> List<B> families(VineId blockId, Class<B> family) {
        List<B> matching = new java.util.ArrayList<>();
        for (BlockBehavior behavior : descriptorOf(blockId).behaviors()) {
            if (family.isInstance(behavior)) {
                matching.add(family.cast(behavior));
            }
        }
        return matching;
    }

    private static BlockDescriptor descriptorOf(VineId blockId) {
        Objects.requireNonNull(blockId, "blockId");
        return VineRegistries.<BlockDescriptor>get(VineContent.BLOCK_TYPE, blockId)
            .map(Holder::value)
            .orElseThrow(() -> new IllegalStateException("no block descriptor registered for " + blockId
                + " — a cell asked the behavior dispatch about a block it never materialized"));
    }
}
