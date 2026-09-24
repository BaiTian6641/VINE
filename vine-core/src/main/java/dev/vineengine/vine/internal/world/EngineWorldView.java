package dev.vineengine.vine.internal.world;

import java.util.Objects;
import java.util.Optional;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.Property;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.internal.spi.WorldViewDriver;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;
import dev.vineengine.vine.world.VineWorld;

/**
 * The engine's world view (sub-07 Stage B): descriptor validation in front of a
 * cell's {@link WorldViewDriver}. The driver owns native mapping; everything that
 * is engine policy lives here, so every cell enforces the same rules:
 *
 * <ul>
 *   <li>only registered engine blocks are addressable — a state naming an unknown
 *       block is a caller bug, reported as one;</li>
 *   <li>a state's schema must be exactly the descriptor's declared properties (same
 *       order, same declared values) — a state built against another schema is
 *       never reinterpreted positionally;</li>
 *   <li>what the world says is the world's: an unloaded dimension, an unloaded
 *       chunk or a non-engine block all report an absent state.</li>
 * </ul>
 *
 * <p>The view holds no state of its own and stays valid for the process lifetime;
 * every call reaches the live world.
 */
public final class EngineWorldView implements VineWorld {

    private final VineId dimensionId;
    private final WorldViewDriver driver;

    public EngineWorldView(VineId dimensionId, WorldViewDriver driver) {
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.driver = Objects.requireNonNull(driver, "driver");
    }

    @Override
    public VineId id() {
        return dimensionId;
    }

    @Override
    public boolean isLoaded() {
        return driver.isLoaded(dimensionId);
    }

    @Override
    public Optional<BlockState> stateAt(BlockPos pos) {
        Objects.requireNonNull(pos, "pos");
        Optional<BlockState> state = driver.stateAt(dimensionId, pos);
        if (state.isEmpty()) {
            return state;
        }
        // A driver that reports a block the engine never registered is broken:
        // its state mapping and the engine's registry disagree, which must be
        // visible rather than surfacing as a state no consumer can act on.
        BlockState found = state.get();
        if (descriptorOf(found.blockId()).isEmpty()) {
            throw new IllegalStateException("world view for " + dimensionId + " reported engine block "
                + found.blockId() + " at " + pos.asString() + " that is not a registered block descriptor");
        }
        return state;
    }

    @Override
    public boolean setState(BlockPos pos, BlockState state) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(state, "state");
        BlockDescriptor descriptor = descriptorOf(state.blockId()).orElseThrow(() -> new IllegalArgumentException(
            "world view for " + dimensionId + " cannot set " + state.blockId() + ": no such block descriptor is"
                + " registered"));
        if (!descriptor.properties().equals(state.schema())) {
            throw new IllegalArgumentException("world view for " + dimensionId + ": state for " + state.blockId()
                + " carries schema " + names(state.schema()) + " but the descriptor declares "
                + names(descriptor.properties()));
        }
        return driver.setState(dimensionId, pos, state);
    }

    private static Optional<BlockDescriptor> descriptorOf(VineId blockId) {
        return VineRegistries.<BlockDescriptor>get(VineContent.BLOCK_TYPE, blockId)
            .map(dev.vineengine.vine.registry.Holder::value);
    }

    private static java.util.List<String> names(java.util.List<Property<?>> properties) {
        return properties.stream().map(Property::name).toList();
    }
}
