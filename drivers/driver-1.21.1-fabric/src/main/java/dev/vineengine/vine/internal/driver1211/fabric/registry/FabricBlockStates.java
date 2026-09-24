package dev.vineengine.vine.internal.driver1211.fabric.registry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import net.minecraft.block.Block;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.IntProperty;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.Property;
import dev.vineengine.vine.world.BlockState;

/**
 * The Fabric cell's native state model (sub-07 Stage B): the bridge between the
 * engine's flattened properties and the vanilla state machinery this cell speaks
 * (Yarn names — {@code StateManager} for the state definition,
 * {@code net.minecraft.state.property.Property} for a native property).
 * Everything here is mapping, never policy — the engine has already rejected
 * over-budget state models, invalid states and unregistered blocks before a
 * driver is called — and both the block path and the world view go through it, so
 * one descriptor has exactly one native reading.
 *
 * <ul>
 *   <li>{@link #nativeProperty} builds the native carrier of one engine axis:
 *       boolean → {@code BooleanProperty}, bounded integer → {@code IntProperty},
 *       enum → {@link EngineEnumProperty}. A declaration with no native carrier
 *       (a non-contiguous integer run, a boolean order the carrier does not
 *       declare) fails here, naming the property and its values, because trimming
 *       it would change the state identity the author declared.</li>
 *   <li>{@link #engineState} / {@link #nativeState} translate a state in either
 *       direction, always through the descriptor's declared schema (the engine's
 *       order), never through the native property order (alphabetical by name).</li>
 *   <li>{@link #requireDefaultState} proves the invariant the whole cell rests on:
 *       the native default state is the engine's default state.</li>
 * </ul>
 *
 * <p><b>Construction:</b> a block's state manager is created inside the
 * {@code Block} constructor — {@code Block} allocates its
 * {@code StateManager.Builder}, calls {@code appendProperties(builder)} and builds
 * the manager from it, all before the constructor returns, i.e. before any field
 * of the block subclass can hold the descriptor's property list (Java initializes
 * subclass fields only after the superclass constructor returns). The declaration
 * therefore travels around construction as thread-local state, published by
 * {@link #withStateModel} and consumed by {@link #installStateModel}: static by
 * necessity, thread-scoped so parallel registration of two descriptors can never
 * cross.
 */
public final class FabricBlockStates {

    /** The state model of the block being constructed on this thread; see {@link #withStateModel}. */
    private static final ThreadLocal<List<Property<?>>> CONSTRUCTING = new ThreadLocal<>();

    private FabricBlockStates() {
    }

    /**
     * Builds a block with {@code stateProperties} as its state model.
     *
     * <p>The declaration is published for the duration of {@code construction}
     * because {@code Block}'s constructor asks for it before the instance can
     * remember it (see the class javadoc). Not reentrant: a block construct that
     * happens inside another would silently get the outer descriptor's state model.
     *
     * @throws IllegalStateException when a state model is already being
     *         constructed on this thread — block construction is never nested
     */
    public static <T extends Block> T withStateModel(List<Property<?>> stateProperties, Supplier<T> construction) {
        Objects.requireNonNull(stateProperties, "stateProperties");
        Objects.requireNonNull(construction, "construction");
        if (CONSTRUCTING.get() != null) {
            throw new IllegalStateException("a VINE block state model is already being constructed on this thread"
                + " — materializing a block inside another block's construction has no state model of its own");
        }
        CONSTRUCTING.set(stateProperties);
        try {
            return construction.get();
        } finally {
            CONSTRUCTING.remove();
        }
    }

    /**
     * Installs the state model of the block under construction — the block's one
     * {@code appendProperties} hook.
     *
     * @throws IllegalStateException when the block was constructed outside
     *         {@link #withStateModel}, which would silently materialize it as a
     *         single-state block
     */
    public static void installStateModel(StateManager.Builder<Block, net.minecraft.block.BlockState> builder) {
        List<Property<?>> stateProperties = CONSTRUCTING.get();
        if (stateProperties == null) {
            throw new IllegalStateException("a VINE block was constructed outside FabricBlockStates.withStateModel"
                + " — its declared properties cannot be resolved");
        }
        for (Property<?> property : stateProperties) {
            builder.add(nativeProperty(property));
        }
    }

    /**
     * The native carrier of one engine axis: the cell's whole property mapping.
     *
     * <p>Each carrier's declared values are read from the carrier itself and
     * compared with the engine's declaration, so "the native default equals the
     * engine default" is established here rather than assumed from the carrier's
     * documentation.
     *
     * @throws IllegalStateException when the declared values have no native
     *         carrier — the message names the property and the values
     */
    public static net.minecraft.state.property.Property<?> nativeProperty(Property<?> property) {
        Objects.requireNonNull(property, "property");
        if (property.type() == Boolean.class) {
            return booleanProperty(property);
        }
        if (property.type() == Integer.class) {
            return integerProperty(property);
        }
        if (property.type().isEnum()) {
            return enumProperty(property);
        }
        throw new IllegalStateException("block property " + property.name() + " declares " + property.type().getName()
            + ", which this cell has no native state carrier for (boolean, bounded int and enum are the carriers"
            + " every supported cell has)");
    }

    /**
     * The engine state of {@code nativeState}, read through {@code descriptor}'s
     * declared schema: the block id, the descriptor's properties, and each value
     * as the declared value it denotes (never a foreign instance the descriptor
     * does not hold).
     */
    public static BlockState engineState(BlockDescriptor descriptor, net.minecraft.block.BlockState nativeState) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(nativeState, "nativeState");
        List<Property<?>> schema = descriptor.properties();
        List<Comparable<?>> values = new ArrayList<>(schema.size());
        for (Property<?> property : schema) {
            net.minecraft.state.property.Property<?> nativeProperty =
                nativePropertyOf(nativeState, property.name());
            values.add(declaredValue(property, nativeValue(nativeState, nativeProperty)));
        }
        return new BlockState(descriptor.id(), schema, List.copyOf(values));
    }

    /**
     * The native state of {@code state} on {@code block}'s state manager, starting
     * from the block's default state and moving one declared property at a time —
     * every intermediate state is a real native state.
     */
    public static net.minecraft.block.BlockState nativeState(Block block, BlockState state) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(state, "state");
        StateManager<Block, net.minecraft.block.BlockState> manager = block.getStateManager();
        net.minecraft.block.BlockState nativeState = block.getDefaultState();
        for (int index = 0; index < state.schema().size(); index++) {
            nativeState = setNativeValue(nativeState,
                nativePropertyOf(manager, state.schema().get(index).name()), state.values().get(index));
        }
        return nativeState;
    }

    /**
     * Proves the invariant engine and cell have to agree on: the native default
     * state (the first value of each native property, in the native order) is the
     * engine's default state (the first declared value of each property, in the
     * declared order) — for the same descriptor, the same state.
     *
     * <p>Checked once per block at materialization, where a disagreement is a boot
     * failure with the block named: "freshly placed" would otherwise mean two
     * different states engine-side and game-side, and no later read could tell
     * which one a position holds.
     *
     * @throws IllegalStateException when the native default state is not the
     *         engine's default state
     */
    public static void requireDefaultState(BlockDescriptor descriptor, Block block) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(block, "block");
        BlockState declared = BlockState.defaultState(descriptor);
        BlockState materialized = engineState(descriptor, block.getDefaultState());
        if (!materialized.values().equals(declared.values())) {
            throw new IllegalStateException("block " + descriptor.id() + ": native default state is "
                + materialized.fragment() + " but the descriptor's default state is " + declared.fragment()
                + " — a cell materializes the first value of each property, so the two must be the same state");
        }
        // The same fact in the other direction — the mapping a state write uses must
        // produce that very native default for the engine's default state, so a
        // world write can never disagree with the read that follows it.
        net.minecraft.block.BlockState written = nativeState(block, declared);
        if (!written.equals(block.getDefaultState())) {
            throw new IllegalStateException("block " + descriptor.id() + ": writing the descriptor's default state "
                + declared.fragment() + " yields " + written + " but the native default state is "
                + block.getDefaultState() + " — the native write and read mappings disagree");
        }
    }

    private static net.minecraft.state.property.Property<?> booleanProperty(Property<?> property) {
        List<Boolean> declared = List.copyOf(typed(property, Boolean.class).values());
        BooleanProperty carrier = BooleanProperty.of(property.name());
        List<Boolean> nativeOrder = List.copyOf(carrier.getValues());
        if (!declared.equals(nativeOrder)) {
            throw new IllegalStateException("block property " + property.name() + " declares boolean values " + declared
                + " but this cell's boolean carrier declares " + nativeOrder
                + " — the first value is the native default, so the declared order has no native carrier");
        }
        return carrier;
    }

    private static net.minecraft.state.property.Property<?> integerProperty(Property<?> property) {
        List<Integer> declared = List.copyOf(typed(property, Integer.class).values());
        if (declared.size() < 2) {
            throw new IllegalStateException("block property " + property.name() + " declares integer values " + declared
                + " but this cell's integer carrier needs a range with at least two values");
        }
        for (int index = 1; index < declared.size(); index++) {
            if (declared.get(index) != declared.get(index - 1) + 1) {
                throw new IllegalStateException("block property " + property.name() + " declares integer values "
                    + declared + " which are not one contiguous ascending run — this cell's integer carrier is a"
                    + " bounded range, so a gap has no native carrier");
            }
        }
        return IntProperty.of(property.name(), declared.get(0), declared.get(declared.size() - 1));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static net.minecraft.state.property.Property<?> enumProperty(Property<?> property) {
        Class<? extends Enum> type = (Class<? extends Enum>) property.type();
        return EngineEnumProperty.create(property.name(), (Class) type, (List) List.copyOf(property.values()));
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> Property<T> typed(Property<?> property, Class<T> type) {
        if (property.type() != type) {
            throw new IllegalStateException("block property " + property.name() + " is declared as "
                + property.type().getName() + ", not " + type.getName());
        }
        return (Property<T>) property;
    }

    private static net.minecraft.state.property.Property<?> nativePropertyOf(
            net.minecraft.block.BlockState nativeState, String name) {
        return nativePropertyOf(nativeState.getBlock().getStateManager(), name);
    }

    private static net.minecraft.state.property.Property<?> nativePropertyOf(
            StateManager<Block, net.minecraft.block.BlockState> manager, String name) {
        net.minecraft.state.property.Property<?> nativeProperty = manager.getProperty(name);
        if (nativeProperty == null) {
            throw new IllegalStateException("native block state manager of " + manager.getOwner()
                + " does not carry property " + name + " — the native state manager and the descriptor's schema"
                + " disagree");
        }
        return nativeProperty;
    }

    /**
     * The declared value {@code nativeValue} denotes. Values are compared, not
     * looked up by name: an int carrier speaks numbers and an enum carrier holds
     * the declared constants themselves, so the engine value is always one the
     * descriptor declares.
     */
    private static Comparable<?> declaredValue(Property<?> property, Comparable<?> nativeValue) {
        for (Comparable<?> declared : property.values()) {
            if (declared.equals(nativeValue)) {
                return declared;
            }
        }
        throw new IllegalStateException("native value " + nativeValue + " of block property " + property.name()
            + " is not one of the declared engine values " + property.encodedValues()
            + " — the native state and the descriptor's schema disagree");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Comparable<?> nativeValue(net.minecraft.block.BlockState nativeState,
            net.minecraft.state.property.Property<?> nativeProperty) {
        return nativeState.get((net.minecraft.state.property.Property) nativeProperty);
    }

    /**
     * The value write itself: {@code nativeProperty}'s type parameter is captured
     * from the descriptor's erased axis, so the native state is written through the
     * same typed setter a vanilla block uses, and the cast is the one place the
     * engine's erased declared value meets it.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> net.minecraft.block.BlockState setNativeValue(
            net.minecraft.block.BlockState nativeState,
            net.minecraft.state.property.Property<T> nativeProperty, Comparable<?> value) {
        return nativeState.with(nativeProperty, (T) value);
    }
}
