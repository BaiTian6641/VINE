package dev.vineengine.vine.testmod.content;

import dev.vineengine.vine.content.Property;
import dev.vineengine.vine.world.BlockPos;
import dev.vineengine.vine.world.BlockState;
import dev.vineengine.vine.world.VineWorld;
import dev.vineengine.vine.world.VineWorlds;

/**
 * The Stage B state exemplar's proof surface: reads and moves block state through
 * the engine's world view only — no loader class, no native state, no position
 * arithmetic that the engine does not own. The TCK's {@code state_roundtrip}
 * scenario drives these through {@code /vine_test tck_state_*} and asserts the
 * printed lines, so the engine's state API is exercised exactly as a consumer
 * would use it.
 *
 * <p>Every line is prefixed {@code tck:} and prints the engine's own
 * {@link BlockState#fragment()} (plus the world's loaded flag), never a native
 * state name: what the scenario compares is the engine's state identity, which is
 * the contract under test.
 */
public final class BlockStateExemplar {

    private BlockStateExemplar() {
    }

    /** Prints the engine state at {@code (x, y, z)}, or that the position holds none. */
    public static void read(int x, int y, int z) {
        BlockPos pos = BlockPos.of(x, y, z);
        VineWorld world = VineWorlds.overworld();
        var found = world.stateAt(pos);
        if (found.isEmpty()) {
            // Vanilla blocks, unloaded chunks and out-of-range positions all land
            // here: "absent" is the engine's answer for "not an engine block".
            System.out.println("tck: state @" + pos.asString() + " absent loaded=" + world.isLoaded());
            return;
        }
        BlockState state = found.get();
        System.out.println("tck: state @" + pos.asString() + " block=" + state.blockId()
            + " state=" + state.fragment() + " loaded=" + world.isLoaded());
    }

    /**
     * Sets {@code propertyName} to {@code rawValue} at {@code (x, y, z)} through
     * the engine world view and prints the state read back from the world after
     * the write — the round-trip evidence, not the value the caller passed in.
     */
    public static void set(int x, int y, int z, String propertyName, String rawValue) {
        BlockPos pos = BlockPos.of(x, y, z);
        VineWorld world = VineWorlds.overworld();
        BlockState before = world.stateAt(pos).orElseThrow(() -> new IllegalStateException(
            "state exemplar: no engine block at " + pos.asString() + " — place " + TestContent.STATEBLOCK_ID
                + " there first"));
        Property<?> property = before.schema().stream()
            .filter(candidate -> candidate.name().equals(propertyName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("state exemplar: " + before.blockId()
                + " has no property '" + propertyName + "' (declared: "
                + before.schema().stream().map(Property::name).toList() + ")"));
        BlockState requested = withValue(before, property, rawValue);
        boolean applied = world.setState(pos, requested);
        String reread = world.stateAt(pos).map(BlockState::fragment).orElse("absent");
        System.out.println("tck: state-set @" + pos.asString() + " property=" + propertyName + " value=" + rawValue
            + " applied=" + applied + " before=" + before.fragment() + " after=" + reread);
    }

    /**
     * The typed single-property mutation: {@code property} carries its own value
     * type, so the value is parsed by the property that declares it and the
     * unchecked cast is the only place the engine's erased property list meets a
     * typed setter.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<?> property, String raw) {
        Property<T> typed = (Property<T>) property;
        return state.with(typed, typed.parse(raw));
    }
}
