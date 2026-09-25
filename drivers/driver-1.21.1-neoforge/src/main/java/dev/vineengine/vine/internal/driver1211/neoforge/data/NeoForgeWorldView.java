package dev.vineengine.vine.internal.driver1211.neoforge.data;

import java.util.Optional;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.LevelStem;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.internal.driver1211.neoforge.boot.NeoForge1211Driver;
import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeBlockStates;
import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeContentMaterializer;
import dev.vineengine.vine.internal.spi.WorldViewDriver;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.data.BlockEntityTarget;
import dev.vineengine.vine.data.VoxelTarget;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;

/**
 * The NeoForge cell's {@link WorldViewDriver} (sub-07 Stage B): how the engine
 * reads and moves block state in this cell's live levels. Bound once during
 * driver bootstrap — the mirror of the storage seam's one-bind rule, since a
 * process has exactly one world to view.
 *
 * <p><b>Resolution, not one hard-coded level:</b> a dimension id is resolved
 * through the server's vanilla dimension registry and then its live level map, so
 * every dimension this cell knows is addressable (the exemplar uses only the
 * overworld) and an id the cell has never heard of is simply not loaded.
 *
 * <p><b>What counts as an engine block:</b> the native block must be one this cell
 * materialized ({@link NeoForgeContentMaterializer.EngineBlock}), and its id must
 * still resolve to an engine descriptor. Anything else at the position — air, a
 * vanilla block, another mod's block — is reported as the world's answer; a
 * materialized engine block whose descriptor the store no longer holds is a
 * broken cell invariant and is reported as one rather than turned into a state
 * no consumer could act on.
 *
 * <p><b>Invariants:</b> reads go to the live world on every call; the only write
 * is {@link #setState}, which refuses (never throws) when the cell cannot satisfy
 * the request, and answers with the state the position holds afterwards.
 */
public final class NeoForgeWorldView implements WorldViewDriver {

    @Override
    public Optional<BlockState> stateAt(VineId dimensionId, BlockPos pos) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return Optional.empty();
        }
        net.minecraft.world.level.block.state.BlockState nativeState = level.getBlockState(nativePos(pos));
        if (!(nativeState.getBlock() instanceof NeoForgeContentMaterializer.EngineBlock engineBlock)) {
            return Optional.empty();
        }
        return Optional.of(NeoForgeBlockStates.engineState(descriptorOf(engineBlock.vineId()), nativeState));
    }

    @Override
    public boolean setState(VineId dimensionId, BlockPos pos, BlockState state) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return false;
        }
        Block nativeBlock = NeoForgeContentMaterializer.blockFor(state.blockId());
        if (nativeBlock == null) {
            return false;
        }
        net.minecraft.core.BlockPos nativePos = nativePos(pos);
        if (level.getBlockState(nativePos).getBlock() != nativeBlock) {
            // The position does not hold that state's block (something else, or the
            // engine block of another id): refused rather than replacing a block the
            // caller never named.
            return false;
        }
        net.minecraft.world.level.block.state.BlockState requested = NeoForgeBlockStates.nativeState(nativeBlock, state);
        // The answer is the state the position holds afterwards, not what setBlock
        // returned: false there means "unchanged", which is exactly the
        // already-holding case the SPI counts as applied, while a refused write
        // shows up as a position that still holds something else.
        if (!level.getBlockState(nativePos).equals(requested)) {
            level.setBlock(nativePos, requested, Block.UPDATE_ALL);
        }
        return level.getBlockState(nativePos).equals(requested);
    }

    @Override
    public Optional<VoxelTarget> holderTarget(VineId dimensionId, BlockPos pos) {
        ServerLevel level = level(dimensionId);
        if (level == null) {
            return Optional.empty();
        }
        // Only this cell's own carrier qualifies: another mod's block entity at the
        // same position is not an engine holder, and answering with its target would
        // attach engine data to somebody else's save tag.
        net.minecraft.world.level.block.entity.BlockEntity holder = level.getBlockEntity(nativePos(pos));
        if (!(holder instanceof NeoForgeContentMaterializer.EngineBlockEntity)) {
            return Optional.empty();
        }
        return Optional.of(new BlockEntityTarget(holder));
    }

    @Override
    public boolean isLoaded(VineId dimensionId) {
        return level(dimensionId) != null;
    }

    /**
     * {@code dimensionId}'s live level, or {@code null} when this cell cannot answer
     * for it: no server yet (before start, after stop), an id the vanilla dimension
     * registry does not know (the cell has no such dimension), or a dimension the
     * registry knows but has not loaded.
     */
    private static ServerLevel level(VineId dimensionId) {
        MinecraftServer server = NeoForge1211Driver.currentServer();
        if (server == null) {
            return null;
        }
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(dimensionId.namespace(), dimensionId.path());
        // The level-stem registry ("dimension" in datapack terms) is this cell's
        // authority on which dimension ids exist; the live level map is keyed by the
        // same locations, so an id the registry does not know has no level to find.
        Registry<LevelStem> dimensions = server.registryAccess().registryOrThrow(Registries.LEVEL_STEM);
        if (!dimensions.containsKey(location)) {
            return null;
        }
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, location));
    }

    /** The descriptor a block this cell materialized resolves to; a failure here is a broken boot invariant. */
    private static BlockDescriptor descriptorOf(VineId blockId) {
        return VineRegistries.get(VineContent.BLOCK_TYPE, blockId).map(Holder::value)
            .orElseThrow(() -> new IllegalStateException("native block " + blockId + " materialized by this cell has"
                + " no engine block descriptor — the native registry and the engine store disagree"));
    }

    private static net.minecraft.core.BlockPos nativePos(BlockPos pos) {
        return new net.minecraft.core.BlockPos(pos.x(), pos.y(), pos.z());
    }
}
