package dev.vineengine.vine.internal.driver1211.neoforge.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockDropsEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.VinePlayer;
import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.BlockEntityDescriptor;
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
 * The NeoForge cell's half of sub-07 Stage C (behavior composition): the place where
 * the engine's per-block plan becomes this cell's native wiring, and where the cell
 * counts what it installed. It is the loader-side twin of the Fabric cell's
 * {@code FabricBehaviorWiring} — same three families, same "one plan decides
 * everything" rule, different native seams.
 *
 * <p><b>One plan, three families, nothing else.</b> Every materialized block asks
 * {@code BehaviorDispatch.plan} once, keeps that answer on its native block
 * ({@link NeoForgeContentMaterializer.EngineBlock#vinePlan()}) and gets exactly the
 * wiring the answer names — a block that declares nothing gets no ticker, no native
 * interaction path and no drop event (Minimal Footprint, the rule this stage's boot
 * line counts):
 *
 * <ul>
 *   <li><b>tick</b> — {@link NeoForgeContentMaterializer.EngineBlockEntityBlock}
 *       answers vanilla's {@code EntityBlock#getTicker} with a ticker only when the
 *       plan ticks, so this cell's native block-entity tick is the only clock
 *       involved. The interval is counted <em>per holder</em>
 *       ({@link NeoForgeContentMaterializer.EngineBlockEntity}), never as a modulo of
 *       absolute game time, so "{@code n} ticks / interval" holds wherever the holder
 *       was created or reloaded.</li>
 *   <li><b>use</b> — {@link NeoForgeContentMaterializer.EngineBlock#useItemOn} is the
 *       native block-interaction layer this cell dispatches from, and it dispatches
 *       only when the plan declares a use behavior; a block that declares none keeps
 *       vanilla's own answer untouched ({@code super}).</li>
 *   <li><b>loot</b> — the {@link BlockDropsEvent} listener below, registered the first
 *       time a block that declares a loot behavior is materialized; a cell whose
 *       content declares none registers no listener at all.</li>
 * </ul>
 *
 * <p><b>Why the interaction hook is the block, not
 * {@code PlayerInteractEvent.RightClickBlock}:</b> the event is posted by exactly one
 * vanilla caller, {@code ServerPlayerGameMode#useItemOn}, and it is <em>that</em>
 * caller which then reaches the block through {@code BlockState#useItemOn}. NF's own
 * interaction helper ({@code GameTestHelper#useBlock}, the path this stage's GameTest
 * proof must exercise) calls {@code BlockState#useItemOn} directly and never posts the
 * event, so an event-only hook would leave a real interaction unreachable from the
 * helper the acceptance names — and installing both layers would run a real player's
 * use behavior twice, since nothing in the event's contract stops vanilla's call from
 * following it. {@code Block#useItemOn} is the one layer both a real player's
 * right-click and the helper pass through, and it carries the hand, the face and the
 * hit point the engine's {@link BlockUseContext} promises. The event remains the right
 * seam for a cell that must also police item use; this cell's behaviors only decide
 * the block interaction, so the block is the honest hook.
 *
 * <p><b>Why the loot hook is the drops event, not a Global Loot Modifier:</b> a GLM
 * ({@code IGlobalLootModifier} + a serializer in
 * {@code NeoForgeRegistries.GLOBAL_LOOT_MODIFIER_SERIALIZERS} + a datapack file) is a
 * <em>loot-table content</em> seam: it fires inside {@code LootTable#getRandomItems}
 * for every loot roll in the game — chests, mobs, fishing, every block break — is
 * enabled by datapack content rather than by this cell's plan, and reports the loot
 * context of whatever table was rolled. {@link BlockDropsEvent} fires at the one
 * moment the engine's {@link LootContext} describes — a block's drops have been
 * determined, before they enter the world — carries the responsible breaker, and hands
 * the cell the very drop list the engine's sink feeds. It is therefore the closer seam
 * for "a loot behavior modifies what this block drops", and it needs no datapack asset
 * whose absence would silently disable the family.
 *
 * <p><b>Counts:</b> the three install counters are incremented where the work happens —
 * the three installation decisions themselves ({@link #installedTicker()},
 * {@link #installedUseHook()}, {@link #installedLootPath()}) — and
 * {@link #LOOT_APPLIED} after each loot dispatch, so the boot line reports what this
 * cell armed and the loot line reports what it actually applied, never a recomputation
 * of descriptors. The boot line's ticker count is the number the engine's own
 * {@code [VINE] behaviors: ticking block entities=N} line asks to be compared with.
 */
public final class NeoForgeBehaviorWiring {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeBehaviorWiring.class);

    /** Blocks whose materialization armed a native ticker. */
    private static final AtomicInteger INSTALLED_TICKERS = new AtomicInteger();

    /** Blocks whose materialization armed the native interaction hook. */
    private static final AtomicInteger INSTALLED_USE = new AtomicInteger();

    /** Blocks whose materialization armed the native drop path. */
    private static final AtomicInteger INSTALLED_LOOT = new AtomicInteger();

    /** Loot dispatches this cell applied — the running count the loot line reports. */
    private static final AtomicInteger LOOT_APPLIED = new AtomicInteger();

    /** Whether the drops listener is registered — armed by the first loot block, never before. */
    private static final AtomicBoolean LOOT_PATH = new AtomicBoolean();

    private NeoForgeBehaviorWiring() {
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
     * drops listener on the first of them: loot wiring exists only where something
     * declares loot, so a cell whose content declares none costs the game bus nothing.
     */
    static void installedLootPath() {
        INSTALLED_LOOT.incrementAndGet();
        if (LOOT_PATH.compareAndSet(false, true)) {
            NeoForge.EVENT_BUS.addListener(BlockDropsEvent.class, NeoForgeBehaviorWiring::lootOnDrops);
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
     * {@link NeoForgeContentMaterializer.EngineBlockEntityBlock} hands vanilla).
     *
     * <p><b>Interval:</b> the holder counts its own ticks, so the first dispatch lands
     * {@code tickInterval} ticks after the holder began ticking and every
     * {@code tickInterval} ticks after that — independent of absolute game time and of
     * when the holder was created. A holder whose descriptor ticks but which declares
     * no {@code TickBehavior} is left alone: the engine's families are the only reason
     * to open a tree or run anything.
     *
     * <p><b>The tree:</b> opened for this very holder through {@code VineData.of}, so
     * the behavior writes into the holder's own attach point — the one storage, save
     * and a consumer's {@code VineWorld.dataAt} read use — and flushed within the same
     * tick, because that read decodes the holder's stored payload. The open is
     * deliberately per dispatch rather than a tree held across ticks: {@code
     * flushDirty} persists the tree the driver's own {@code open} returned for the
     * target, so a tree held across ticks would silently lose its writes the moment
     * anything else opens the same holder (a consumer reading the block does exactly
     * that).
     */
    static void tickBlockEntity(Level level, BlockPos pos, BlockState nativeState,
            NeoForgeContentMaterializer.EngineBlockEntity holder) {
        if (!(nativeState.getBlock() instanceof NeoForgeContentMaterializer.EngineBlock engineBlock)) {
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
        VineId schemaId = descriptor.blockEntity().map(BlockEntityDescriptor::schemaId)
            .orElseThrow(() -> new IllegalStateException("block " + descriptor.id() + " ticks without a block-entity"
                + " declaration — its plan could not have asked for a ticker"));
        BlockEntityTarget target = holder.vineTarget();
        VoxelData tree = VineData.of(target, schemaId);
        BehaviorDispatch.tick(descriptor.id(), new BlockTickContext(viewOf(level), enginePos(pos),
            engineState(descriptor, nativeState), TickKind.BLOCK_ENTITY, Optional.of(tree)));
        flush(target, schemaId, tree);
    }

    /**
     * The native use hook of a block whose plan declares a use behavior: builds the
     * engine's view of the interaction and maps the dispatch's answer onto this cell's
     * own interaction result.
     *
     * <p>Mapping: {@link UseResult#HANDLED} is vanilla's {@code SUCCESS} (the
     * interaction is consumed), {@link UseResult#DENIED} is vanilla's {@code FAIL} —
     * the block refuses it, which is the same constant the Fabric cell maps DENIED
     * onto, so neither cell invents a refusal the other does not have — and
     * {@link UseResult#PASS} is {@code PASS_TO_DEFAULT_BLOCK_INTERACTION}, the answer
     * a block with no use behavior gives.
     */
    static ItemInteractionResult useBlock(NeoForgeContentMaterializer.EngineBlock engineBlock, BlockState nativeState,
            Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        BlockDescriptor descriptor = descriptorOf(engineBlock.vineId());
        UseResult result = BehaviorDispatch.use(descriptor.id(), new BlockUseContext(
            viewOf(level), enginePos(pos), engineState(descriptor, nativeState), playerOf(player),
            engineHand(hand), engineFace(hit.getDirection()),
            Vec3.of(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z)));
        return switch (result) {
            case HANDLED -> ItemInteractionResult.SUCCESS;
            case DENIED -> ItemInteractionResult.FAIL;
            case PASS -> ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        };
    }

    /**
     * This cell's native loot path: {@link BlockDropsEvent}, posted by NeoForge's
     * {@code CommonHooks#handleBlockDrops} from every {@code Block#dropResources} call
     * — a player's break, a {@code /setblock destroy}, an explosion, a world edit —
     * once per drop resolution, with the responsible entity when there is one and the
     * drop list the game is about to spawn.
     *
     * <p>The dispatch happens only for a block whose plan declares a loot behavior, and
     * the sink adds to the event's own drop list, so a contribution is spawned exactly
     * the way the block's other drops are. The applied count is printed after every
     * dispatch, in the stage's fixed shape {@code vine: behaviors loot applied=<n>},
     * because a sink that wrote "somewhere" would not be observable on its own.
     *
     * <p>Never cancels: a {@code LootBehavior} adds to a block's drops, it does not
     * forbid the removal, and vanilla only spawns the list when the event lets it.
     */
    static void lootOnDrops(BlockDropsEvent event) {
        if (!(event.getState().getBlock() instanceof NeoForgeContentMaterializer.EngineBlock engineBlock)
                || !engineBlock.vinePlan().loot()) {
            return;
        }
        ServerLevel level = event.getLevel();
        BlockPos pos = event.getPos();
        BlockDescriptor descriptor = descriptorOf(engineBlock.vineId());
        Entity breaker = event.getBreaker();
        List<ItemEntity> additions = new ArrayList<>();
        BehaviorDispatch.loot(descriptor.id(), new LootContext(viewOf(level), enginePos(pos),
            engineState(descriptor, event.getState()),
            breaker instanceof Player player ? Optional.of(playerOf(player)) : Optional.empty()),
            (itemId, count) -> additions.add(drop(level, pos, itemId, count)));
        event.getDrops().addAll(additions);
        LOG.info("vine: behaviors loot applied=" + LOOT_APPLIED.incrementAndGet());
    }

    /**
     * One sink entry as a native drop: a behavior names engine items only, and an id
     * this cell materialized no item for (or a non-positive count) is an author bug
     * reported rather than turned into a silent nothing. The stack is spawned the way
     * vanilla spawns a block's own drops — at the block's centre, with the default
     * pick-up delay — so a behavior's contribution is indistinguishable from a
     * vanilla one.
     */
    private static ItemEntity drop(ServerLevel level, BlockPos pos, VineId itemId, int count) {
        Item item = NeoForgeContentMaterializer.itemFor(itemId);
        if (item == null) {
            throw new IllegalArgumentException("loot drop " + itemId + " is not an item this cell materialized — a"
                + " LootSink entry may only name registered engine items");
        }
        if (count <= 0) {
            throw new IllegalArgumentException("loot drop " + itemId + " asked for " + count + " items — a LootSink"
                + " entry must add at least one");
        }
        ItemEntity entity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
            new ItemStack(item, count));
        entity.setDefaultPickUpDelay();
        return entity;
    }

    /**
     * Persists {@code tree} at {@code target} — the write that makes a behavior's tick
     * land in the holder's payload. The storage driver's flush resolves the tree through
     * its own per-holder open, so the open in {@link #tickBlockEntity} and this call
     * must name the same instance.
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

    /** The engine's view over the dimension {@code level} is — the context's world handle. */
    private static VineWorld viewOf(Level level) {
        ResourceLocation key = level.dimension().location();
        return VineWorlds.of(VineId.of(key.getNamespace(), key.getPath()));
    }

    /**
     * The interacting player as the engine's facade. Wrapped from the interaction's own
     * player object rather than resolved by UUID: a GameTest's mock player (and any
     * interaction the server has not registered) is still the player who interacted.
     */
    private static VinePlayer playerOf(Player player) {
        return new VinePlayer() {
            @Override
            public UUID uniqueId() {
                return player.getUUID();
            }

            @Override
            public String name() {
                return player.getName().getString();
            }
        };
    }

    /** The engine's hand for a native one — mapped by name, never by ordinal. */
    private static dev.vineengine.vine.world.Hand engineHand(InteractionHand hand) {
        return switch (hand) {
            case MAIN_HAND -> dev.vineengine.vine.world.Hand.MAIN;
            case OFF_HAND -> dev.vineengine.vine.world.Hand.OFF;
        };
    }

    /** The engine's face for a native direction — mapped by name, never by ordinal. */
    private static Direction engineFace(net.minecraft.core.Direction direction) {
        return switch (direction) {
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
    private static dev.vineengine.vine.world.BlockState engineState(BlockDescriptor descriptor,
            BlockState nativeState) {
        return NeoForgeBlockStates.engineState(descriptor, nativeState);
    }

    /** The descriptor a block this cell materialized resolves to; a failure here is a broken boot invariant. */
    private static BlockDescriptor descriptorOf(VineId blockId) {
        return VineRegistries.<BlockDescriptor>get(VineContent.BLOCK_TYPE, blockId).map(Holder::value)
            .orElseThrow(() -> new IllegalStateException("native block " + blockId + " materialized by this cell has no"
                + " engine block descriptor — the native registry and the engine store disagree"));
    }
}
