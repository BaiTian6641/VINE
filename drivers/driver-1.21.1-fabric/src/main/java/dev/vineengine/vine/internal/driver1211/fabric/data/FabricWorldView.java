package dev.vineengine.vine.internal.driver1211.fabric.data;

import java.util.Optional;

import net.minecraft.block.Block;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.internal.driver1211.fabric.boot.Fabric1211Driver;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricBlockStates;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer;
import dev.vineengine.vine.internal.spi.WorldViewDriver;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.data.BlockEntityTarget;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;

/**
 * The Fabric cell's {@link WorldViewDriver} (sub-07 Stage B): how the engine
 * reads and moves block state in this cell's live worlds. Bound once during
 * driver bootstrap — the mirror of the storage seam's one-bind rule, since a
 * process has exactly one world to view.
 *
 * <p><b>Resolution, not one hard-coded level:</b> a dimension id is resolved
 * through the server's vanilla dimension (level-stem) registry and then its live
 * world map, so every dimension this cell knows is addressable (the exemplar uses
 * only the overworld) and an id the cell has never heard of is simply not loaded.
 *
 * <p><b>What counts as an engine block:</b> the native block must be one this cell
 * materialized ({@link FabricContentMaterializer.EngineBlock}), and its id must
 * still resolve to an engine descriptor. Anything else at the position — air, a
 * vanilla block, another mod's block — is reported as the world's answer; a
 * materialized engine block whose descriptor the store no longer holds is a
 * broken cell invariant and is reported as one rather than turned into a state no
 * consumer could act on.
 *
 * <p><b>Invariants:</b> reads go to the live world on every call; the only write
 * is {@link #setState}, which refuses (never throws) when the cell cannot satisfy
 * the request, and answers with the state the position holds afterwards.
 */
public final class FabricWorldView implements WorldViewDriver {

    @Override
    public Optional<BlockState> stateAt(VineId dimensionId, BlockPos pos) {
        ServerWorld world = nativeWorld(dimensionId);
        if (world == null) {
            return Optional.empty();
        }
        net.minecraft.block.BlockState nativeState = world.getBlockState(nativePos(pos));
        if (!(nativeState.getBlock() instanceof FabricContentMaterializer.EngineBlock engineBlock)) {
            return Optional.empty();
        }
        return Optional.of(FabricBlockStates.engineState(descriptorOf(engineBlock.vineId()), nativeState));
    }

    @Override
    public boolean setState(VineId dimensionId, BlockPos pos, BlockState state) {
        ServerWorld world = nativeWorld(dimensionId);
        if (world == null) {
            return false;
        }
        Block nativeBlock = FabricContentMaterializer.blockFor(state.blockId());
        if (nativeBlock == null) {
            return false;
        }
        net.minecraft.util.math.BlockPos nativePos = nativePos(pos);
        if (world.getBlockState(nativePos).getBlock() != nativeBlock) {
            // The position does not hold that state's block (something else, or the
            // engine block of another id): refused rather than replacing a block the
            // caller never named.
            return false;
        }
        net.minecraft.block.BlockState requested = FabricBlockStates.nativeState(nativeBlock, state);
        // The answer is the state the position holds afterwards, not what
        // setBlockState returned: false there means "unchanged", which is exactly
        // the already-holding case the SPI counts as applied, while a refused write
        // shows up as a position that still holds something else.
        if (!world.getBlockState(nativePos).equals(requested)) {
            world.setBlockState(nativePos, requested, Block.NOTIFY_ALL);
        }
        return world.getBlockState(nativePos).equals(requested);
    }

    @Override
    public Optional<VoxelTarget> holderTarget(VineId dimensionId, BlockPos pos) {
        ServerWorld level = nativeWorld(dimensionId);
        if (level == null) {
            return Optional.empty();
        }
        // Only this cell's own carrier qualifies: another mod's block entity at the
        // same position is not an engine holder, and answering with its target would
        // attach engine data to somebody else's save tag.
        net.minecraft.block.entity.BlockEntity holder = level.getBlockEntity(nativePos(pos));
        if (!(holder instanceof FabricContentMaterializer.EngineBlockEntity)) {
            return Optional.empty();
        }
        return Optional.of(new BlockEntityTarget(holder));
    }

    @Override
    public boolean isLoaded(VineId dimensionId) {
        return nativeWorld(dimensionId) != null;
    }

    /**
     * {@code dimensionId}'s live world, or {@code null} when this cell cannot
     * answer for it: no server yet (before start, after stop), an id the vanilla
     * dimension registry does not know (the cell has no such dimension), or a
     * dimension the registry knows but has not loaded.
     *
     * <p>Shared with the entity spawn path (sub-08 Stage A,
     * {@code FabricEntityDriver}): "which live world is this engine dimension"
     * has exactly one answer on this cell, so the state view and the entity
     * primitive must not each implement it.
     */
    public static ServerWorld nativeWorld(VineId dimensionId) {
        MinecraftServer server = Fabric1211Driver.currentServer();
        if (server == null) {
            return null;
        }
        Identifier location = Identifier.of(dimensionId.namespace(), dimensionId.path());
        // The level-stem registry ("dimension" in datapack terms) is this cell's
        // authority on which dimension ids exist; the live world map is keyed by the
        // same locations ("world" in registry terms), so an id the registry does not
        // know has no world to find.
        Registry<DimensionOptions> dimensions =
            server.getRegistryManager().getOptional(RegistryKeys.DIMENSION).orElse(null);
        if (dimensions == null || !dimensions.containsId(location)) {
            return null;
        }
        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, location));
    }

    /** The descriptor a block this cell materialized resolves to; a failure here is a broken boot invariant. */
    private static BlockDescriptor descriptorOf(VineId blockId) {
        return VineRegistries.get(VineContent.BLOCK_TYPE, blockId).map(Holder::value)
            .orElseThrow(() -> new IllegalStateException("native block " + blockId + " materialized by this cell has"
                + " no engine block descriptor — the native registry and the engine store disagree"));
    }

    private static net.minecraft.util.math.BlockPos nativePos(BlockPos pos) {
        return new net.minecraft.util.math.BlockPos(pos.x(), pos.y(), pos.z());
    }
}
