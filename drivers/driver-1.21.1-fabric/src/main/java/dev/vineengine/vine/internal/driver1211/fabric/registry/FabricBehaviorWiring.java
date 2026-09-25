package dev.vineengine.vine.internal.driver1211.fabric.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.ItemActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.BlockTickContext;
import dev.vineengine.vine.content.BlockUseContext;
import dev.vineengine.vine.content.LootContext;
import dev.vineengine.vine.content.TickKind;
import dev.vineengine.vine.content.UseResult;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.data.BlockEntityTarget;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.internal.content.BehaviorDispatch;
import dev.vineengine.vine.internal.data.VoxelStorageBinding;
import dev.vineengine.vine.internal.spi.VoxelStorageDriver;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.Direction;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorld;
import dev.vineengine.vine.world.VineWorlds;

/**
 * The Fabric cell's half of sub-07 Stage C (behavior composition): the place where
 * the engine's per-block plan becomes this cell's native wiring, and where the cell
 * counts what it installed.
 *
 * <p><b>One plan, three families, nothing else.</b> Every materialized block asks
 * {@code BehaviorDispatch.plan} once, keeps that answer on its native block
 * ({@link FabricContentMaterializer.EngineBlock#vinePlan()}) and gets exactly the
 * wiring the answer names — a block that declares nothing gets no ticker, no
 * interaction hook and no loot path (Minimal Footprint, the rule this stage's boot
 * line counts):
 *
 * <ul>
 *   <li><b>tick</b> — {@link FabricContentMaterializer.EngineBlockEntityBlock}
 *       answers vanilla's {@code BlockEntityProvider#getTicker} with a ticker only
 *       when the plan ticks, so this cell's native block-entity tick is the only
 *       clock involved. The interval is counted <em>per holder</em>
 *       ({@link FabricContentMaterializer.EngineBlockEntity}), never as a modulo of
 *       absolute game time, so "{@code n} ticks / interval" holds wherever the holder
 *       was created or reloaded.</li>
 *   <li><b>use</b> — {@link FabricContentMaterializer.EngineBlock#onUseWithItem} is
 *       this cell's native block-interaction hook (the one vanilla's
 *       {@code ServerPlayerInteractionManager#interactBlock} calls first, which is
 *       also the only native layer that knows the hand), and it dispatches only when
 *       the plan declares a use behavior; a block that declares none keeps vanilla's
 *       own answer untouched.</li>
 *   <li><b>loot</b> — the break-time listener below, registered the first time a block
 *       that declares a loot behavior is materialized; a cell whose content declares
 *       none registers no listener at all.</li>
 * </ul>
 *
 * <p><b>Why the loader's loot event is not this path:</b> Fabric's loot event
 * ({@code LootTableEvents.MODIFY}) is a per-loot-table <em>build</em> callback — it
 * sees a table's builder once, when the table is loaded, and never a break's position,
 * state or player. A {@link dev.vineengine.vine.content.LootBehavior} is the other
 * shape: engine code that must run per removal with a {@link LootContext}. So the
 * dispatch hangs off the block's own removal ({@code AbstractBlock#onStateReplaced}),
 * which every cause passes through — a player's break, an explosion, a piston, a world
 * edit — and the responsible player, which only the loader's break event names, is
 * recorded when it is offered and consumed by that removal. A cell whose content
 * declares no loot behavior registers neither listener and dispatches nothing.
 *
 * <p><b>Counts:</b> the four counters are incremented where the work happens — the
 * three install counters at the installation decisions themselves
 * ({@link #installedTicker()}, {@link #installedUseHook()}, {@link #installedLootPath()})
 * and {@link #LOOT_APPLIED} after each loot dispatch — so the boot line reports what
 * this cell armed and the loot line reports what it actually applied, never a
 * recomputation of descriptors. The boot line's ticker count is the number the engine's
 * own {@code [VINE] behaviors: ticking block entities=N} line asks to be compared with.
 */
public final class FabricBehaviorWiring {

    private static final Logger LOG = LoggerFactory.getLogger(FabricBehaviorWiring.class);

    /** Blocks whose materialization armed a native ticker. */
    private static final AtomicInteger INSTALLED_TICKERS = new AtomicInteger();

    /** Blocks whose materialization armed the native interaction hook. */
    private static final AtomicInteger INSTALLED_USE = new AtomicInteger();

    /** Blocks whose materialization armed the native loot path. */
    private static final AtomicInteger INSTALLED_LOOT = new AtomicInteger();

    /** Loot dispatches this cell applied — the running count the loot line reports. */
    private static final AtomicInteger LOOT_APPLIED = new AtomicInteger();

    /** Whether the break-time listeners are registered — armed by the first loot block, never before. */
    private static final AtomicBoolean LOOT_PATH = new AtomicBoolean();

    /**
     * The player a break named, waiting for the removal that reports it (world identity
     * plus position, so two dimensions never share an entry). Bounded by construction:
     * an entry is written per break and removed by the removal that follows, or by the
     * cancelled-break callback when vanilla never gets that far.
     */
    private static final Map<BrokenAt, PlayerEntity> BREAKERS = new ConcurrentHashMap<>();

    /** One position a player broke, until the removal consumes it. */
    private record BrokenAt(World world, BlockPos pos) {
    }

    private FabricBehaviorWiring() {
    }

    /**
     * Records one materialized block whose plan ticks; the ticker itself is installed
     * by {@code EngineBlockEntityBlock#getTicker}, which is the same plan's answer.
     */
    static void installedTicker() {
        INSTALLED_TICKERS.incrementAndGet();
    }

    /** Records one materialized block whose plan declares a use behavior. */
    static void installedUseHook() {
        INSTALLED_USE.incrementAndGet();
    }

    /**
     * Records one materialized block whose plan declares a loot behavior, and arms the
     * break-time loot listener on the first of them: loot wiring exists only where
     * something declares loot.
     */
    static void installedLootPath() {
        INSTALLED_LOOT.incrementAndGet();
        if (LOOT_PATH.compareAndSet(false, true)) {
            PlayerBlockBreakEvents.BEFORE.register(FabricBehaviorWiring::beforeBlockBreak);
            PlayerBlockBreakEvents.CANCELED.register(FabricBehaviorWiring::breakCanceled);
        }
    }

    /**
     * The boot line this cell prints beside the engine's own count: what it actually
     * installed. Shape is fixed by the stage's acceptance — {@code
     * vine: behaviors installed tickers=<n> use=<n> loot=<n>}.
     */
    public static String installReport() {
        return "vine: behaviors installed tickers=" + INSTALLED_TICKERS.get()
            + " use=" + INSTALLED_USE.get()
            + " loot=" + INSTALLED_LOOT.get();
    }

    /**
     * One native block-entity tick of a holder whose descriptor ticks (the ticker
     * {@link FabricContentMaterializer.EngineBlockEntityBlock} hands vanilla).
     *
     * <p><b>Interval:</b> the holder counts its own ticks, so the first dispatch lands
     * {@code tickInterval} ticks after the holder began ticking and every
     * {@code tickInterval} ticks after that — independent of absolute game time and of
     * when the holder was created. A holder whose descriptor ticks but which declares
     * no {@code TickBehavior} is left alone: the engine's families are the only reason
     * to open a tree or run anything.
     *
     * <p><b>The tree:</b> opened for this very holder through {@code VineData.of}, so
     * the behavior writes into the holder's own attach point — the one storage, save and
     * a consumer's {@code VineWorld.dataAt} read use — and flushed within the same tick,
     * because that read decodes the holder's stored payload. The open is deliberately
     * per dispatch rather than a tree held across ticks: {@code flushDirty} persists the
     * tree the driver's own {@code open} returned for the target, so a tree held across
     * ticks would silently lose its writes the moment anything else opens the same
     * holder (a consumer reading the block does exactly that).
     */
    static void tickBlockEntity(World world, BlockPos pos, BlockState nativeState,
            FabricContentMaterializer.EngineBlockEntity holder) {
        if (!(nativeState.getBlock() instanceof FabricContentMaterializer.EngineBlock engineBlock)) {
            return;
        }
        BehaviorDispatch.Plan plan = engineBlock.vinePlan();
        if (!plan.tick()) {
            return;
        }
        if (++holder.vineTicks < plan.tickInterval()) {
            return;
        }
        holder.vineTicks = 0;
        BlockDescriptor descriptor = descriptorOf(engineBlock.vineId());
        VineId schemaId = descriptor.blockEntity()
            .map(dev.vineengine.vine.content.BlockEntityDescriptor::schemaId)
            .orElseThrow(() -> new IllegalStateException("block " + descriptor.id() + " ticks without a block-entity"
                + " declaration — its plan could not have asked for a ticker"));
        BlockEntityTarget target = holder.vineTarget();
        VoxelData tree = VineData.of(target, schemaId);
        BehaviorDispatch.tick(descriptor.id(), new BlockTickContext(viewOf(world), enginePos(pos),
            engineState(descriptor, nativeState), TickKind.BLOCK_ENTITY, Optional.of(tree)));
        flush(target, schemaId, tree);
    }

    /**
     * The native use hook of a block whose plan declares a use behavior: builds the
     * engine's view of the interaction and maps the dispatch's answer onto this cell's
     * own outcome.
     *
     * <p>Mapping: {@link UseResult#HANDLED} is vanilla success (the interaction is
     * consumed), {@link UseResult#DENIED} is vanilla failure (the interaction is
     * refused), and {@link UseResult#PASS} hands the interaction back to vanilla's own
     * defaults — the same answer a block with no use behavior gives.
     */
    static ItemActionResult useBlock(FabricContentMaterializer.EngineBlock engineBlock, BlockState nativeState,
            World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit) {
        BlockDescriptor descriptor = descriptorOf(engineBlock.vineId());
        UseResult result = BehaviorDispatch.use(descriptor.id(), new BlockUseContext(
            viewOf(world), enginePos(pos), engineState(descriptor, nativeState), playerOf(player),
            engineHand(hand), engineFace(hit.getSide()),
            Vec3.of(hit.getPos().getX(), hit.getPos().getY(), hit.getPos().getZ())));
        return switch (result) {
            case HANDLED -> ItemActionResult.SUCCESS;
            case DENIED -> ItemActionResult.FAIL;
            case PASS -> ItemActionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        };
    }

    /**
     * Records the player a break names, so the removal that follows can report the
     * responsible player — the one fact about a removal that only this event knows.
     *
     * <p>The dispatch itself does not happen here: a break event that another listener
     * cancels, or that vanilla never carries through, must not run loot behaviors. Never
     * cancels (returns {@code true} in every case) — a {@code LootBehavior} adds to a
     * block's drops, it does not forbid breaking it, and the engine's sink is an adder
     * by construction.
     */
    private static boolean beforeBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState nativeState,
            net.minecraft.block.entity.BlockEntity blockEntity) {
        if (isLootBlock(nativeState)) {
            BREAKERS.put(new BrokenAt(world, pos.toImmutable()), player);
        }
        return true;
    }

    /** A break another listener cancelled never reaches a removal: its record goes with it. */
    private static void breakCanceled(World world, PlayerEntity player, BlockPos pos, BlockState nativeState,
            net.minecraft.block.entity.BlockEntity blockEntity) {
        BREAKERS.remove(new BrokenAt(world, pos.toImmutable()));
    }

    /**
     * This cell's native loot path: the moment a block actually leaves the world
     * ({@code AbstractBlock#onStateReplaced}), for every cause — a player's break, an
     * explosion, a piston, a world edit — called by the sole constructor of the block
     * class, {@link FabricContentMaterializer.EngineBlock}, and only for a block whose
     * plan declares a loot behavior.
     *
     * <p>The sink writes into this cell's own drop mechanism: each contribution becomes a
     * vanilla item stack spawned at the removed block's position, next to whatever the
     * block's own drops are (this cell's materialized blocks have no loot table data, so
     * in practice the additions are the drops). The applied count is printed after every
     * dispatch, in the stage's fixed shape {@code vine: behaviors loot applied=<n>},
     * because a sink that wrote "somewhere" would not be observable on its own.
     */
    static void lootOnRemoval(World world, BlockPos pos, BlockState nativeState) {
        if (world.isClient() || !isLootBlock(nativeState)) {
            return;
        }
        FabricContentMaterializer.EngineBlock engineBlock =
            (FabricContentMaterializer.EngineBlock) nativeState.getBlock();
        BlockDescriptor descriptor = descriptorOf(engineBlock.vineId());
        PlayerEntity breaker = BREAKERS.remove(new BrokenAt(world, pos.toImmutable()));
        List<ItemStack> additions = new ArrayList<>();
        BehaviorDispatch.loot(descriptor.id(), new LootContext(viewOf(world), enginePos(pos),
            engineState(descriptor, nativeState), Optional.ofNullable(breaker).map(FabricBehaviorWiring::playerOf)),
            (itemId, count) -> additions.add(drop(itemId, count)));
        for (ItemStack addition : additions) {
            Block.dropStack(world, pos, addition);
        }
        LOG.info("vine: behaviors loot applied=" + LOOT_APPLIED.incrementAndGet());
    }

    /** Whether this native block is one this cell materialized with a loot behavior. */
    private static boolean isLootBlock(BlockState nativeState) {
        return nativeState.getBlock() instanceof FabricContentMaterializer.EngineBlock engineBlock
            && engineBlock.vinePlan().loot();
    }

    /**
     * One sink entry as a native stack: a behavior names engine items only, and an id
     * this cell materialized no item for (or a non-positive count) is an author bug
     * reported rather than turned into a silent nothing.
     */
    private static ItemStack drop(VineId itemId, int count) {
        Item item = FabricContentMaterializer.itemFor(itemId);
        if (item == null) {
            throw new IllegalArgumentException("loot drop " + itemId + " is not an item this cell materialized — a"
                + " LootSink entry may only name registered engine items");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("loot drop " + itemId + " asked for " + count + " items — a LootSink"
                + " entry must add at least one");
        }
        return new ItemStack(item, count);
    }

    /**
     * Persists {@code tree} at {@code target} — the write that makes a behavior's tick
     * land in the holder's payload. The storage driver's flush resolves the tree through
     * its own per-holder open, so the open above and this call must name the same
     * instance; see {@link #tickBlockEntity}.
     */
    private static void flush(BlockEntityTarget target, VineId schemaId, VoxelData tree) {
        VoxelStorageDriver storage = VoxelStorageBinding.bound();
        VoxelStorageBinding.EngineVoxels handle = VoxelStorageBinding.engine();
        if (storage == null || handle == null) {
            throw new IllegalStateException("a ticking holder reached its tick before this cell bound its storage"
                + " driver — the tick path needs the attach point bootstrap installs");
        }
        storage.flushDirty(target, schemaId, handle.drainDirty(tree));
    }

    /** The engine's view over the dimension {@code world} is — the context's world handle. */
    private static VineWorld viewOf(World world) {
        Identifier key = world.getRegistryKey().getValue();
        return VineWorlds.of(VineId.of(key.getNamespace(), key.getPath()));
    }

    /**
     * The interacting player as the engine's facade. Wrapped from the interaction's own
     * player object rather than resolved by UUID: a GameTest's mock player (and any
     * interaction the server has not registered) is still the player who interacted.
     */
    private static VinePlayer playerOf(PlayerEntity player) {
        return new VinePlayer() {
            @Override
            public UUID uniqueId() {
                return player.getUuid();
            }

            @Override
            public String name() {
                return player.getName().getString();
            }
        };
    }

    /** The engine's hand for a native one — mapped by name, never by ordinal. */
    private static dev.vineengine.vine.world.Hand engineHand(Hand hand) {
        return switch (hand) {
            case MAIN_HAND -> dev.vineengine.vine.world.Hand.MAIN;
            case OFF_HAND -> dev.vineengine.vine.world.Hand.OFF;
        };
    }

    /** The engine's face for a native side — mapped by name, never by ordinal. */
    private static Direction engineFace(net.minecraft.util.math.Direction side) {
        return switch (side) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    private static dev.vineengine.vine.world.BlockPos enginePos(BlockPos pos) {
        return dev.vineengine.vine.world.BlockPos.of(pos.getX(), pos.getY(), pos.getZ());
    }

    /** The engine state of a native state, through the same mapping the world view uses. */
    private static dev.vineengine.vine.world.BlockState engineState(BlockDescriptor descriptor, BlockState nativeState) {
        return FabricBlockStates.engineState(descriptor, nativeState);
    }

    /** The descriptor a block this cell materialized resolves to; a failure here is a broken boot invariant. */
    private static BlockDescriptor descriptorOf(VineId blockId) {
        return VineRegistries.<BlockDescriptor>get(VineContent.BLOCK_TYPE, blockId).map(Holder::value)
            .orElseThrow(() -> new IllegalStateException("native block " + blockId + " materialized by this cell has no"
                + " engine block descriptor — the native registry and the engine store disagree"));
    }
}
