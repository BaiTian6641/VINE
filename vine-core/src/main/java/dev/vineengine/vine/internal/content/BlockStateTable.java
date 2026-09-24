package dev.vineengine.vine.internal.content;

import java.util.ArrayList;
import java.util.List;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.Property;
import dev.vineengine.vine.world.BlockState;

/**
 * The engine-side state table of one block descriptor (sub-07 Stage B internals):
 * the cartesian product of the descriptor's declared properties, guarded by a
 * hard per-block budget. Validation happens at registration — a descriptor whose
 * product is over budget is rejected with a diagnostic that names the product
 * terms, because no cell can carry it and silently trimming the model would
 * change the state identity the author declared.
 *
 * <p><b>Ordering:</b> states are indexed row-major over the descriptor's property
 * list (the first property is the most significant term, the last one varies
 * fastest), and the value order inside a property is its declared order. That is
 * one deterministic total order for every consumer of the table — datagen, traces
 * and fixtures all enumerate the same way, so the same descriptor never produces
 * two different state sequences.
 *
 * <p><b>Invariants:</b> every state in the table is valid by construction (values
 * come from the declared lists); the table is immutable; the budget is checked
 * exactly once, at construction.
 */
public final class BlockStateTable {

    /**
     * The per-block state budget. 512 is the number a cell can materialize without
     * a state definition that dwarfs the block it describes (vanilla's own largest
     * blocks sit far below it), and it is a hard limit: over-budget descriptors are
     * rejected at registration, never clamped.
     */
    public static final int STATE_BUDGET = 512;

    private final BlockDescriptor descriptor;
    private final List<Property<?>> properties;
    private final int stateCount;

    private BlockStateTable(BlockDescriptor descriptor, List<Property<?>> properties, int stateCount) {
        this.descriptor = descriptor;
        this.properties = properties;
        this.stateCount = stateCount;
    }

    /**
     * Builds the table for {@code descriptor}.
     *
     * @throws IllegalArgumentException when the product of the declared value
     *         lists exceeds {@link #STATE_BUDGET}; the message names every product
     *         term and the total
     */
    public static BlockStateTable of(BlockDescriptor descriptor) {
        List<Property<?>> properties = descriptor.properties();
        // The full product, never a prefix: an over-budget diagnostic that reports
        // only the terms up to the first overrun would misstate the model's cost.
        // Each property declares at most 16 values and descriptors declare a
        // handful of properties, so the product fits a long with room to spare.
        long product = 1L;
        for (Property<?> property : properties) {
            product *= property.values().size();
        }
        if (product > STATE_BUDGET) {
            throw new IllegalArgumentException(diagnostic(descriptor, properties, product));
        }
        return new BlockStateTable(descriptor, properties, (int) product);
    }

    /**
     * The registration-time check (sub-07 Stage B): throws when {@code descriptor}
     * is over budget, otherwise returns the built table so callers that want the
     * table do not validate twice.
     */
    public static BlockStateTable validate(BlockDescriptor descriptor) {
        return of(descriptor);
    }

    /** The block this table describes. */
    public BlockDescriptor descriptor() {
        return descriptor;
    }

    /** The declared properties, in flattening order. */
    public List<Property<?>> properties() {
        return properties;
    }

    /** How many states this block has: the product of its declared value counts. */
    public int stateCount() {
        return stateCount;
    }

    /**
     * The default state — the first declared value of each property, identical to
     * the state every cell materializes natively for a freshly placed block.
     */
    public BlockState defaultState() {
        return BlockState.defaultState(descriptor);
    }

    /**
     * The state at {@code index} in the table's total order.
     *
     * @throws IndexOutOfBoundsException when the index is outside
     *         {@code [0, stateCount())}
     */
    public BlockState stateAt(int index) {
        if (index < 0 || index >= stateCount) {
            throw new IndexOutOfBoundsException(descriptor.id() + ": state index " + index + " outside [0, "
                + stateCount + ")");
        }
        List<Comparable<?>> values = new ArrayList<>(properties.size());
        int remainder = index;
        for (int i = properties.size() - 1; i >= 0; i--) {
            List<? extends Comparable<?>> declared = properties.get(i).values();
            values.add(0, declared.get(remainder % declared.size()));
            remainder /= declared.size();
        }
        return new BlockState(descriptor.id(), properties, List.copyOf(values));
    }

    /**
     * The table's state strings, in the total order — the enumeration datagen and
     * fixtures compare, so a block's variant list is derived from its descriptor
     * rather than restated by hand.
     */
    public List<String> variants() {
        List<String> variants = new ArrayList<>(stateCount);
        for (int index = 0; index < stateCount; index++) {
            variants.add(stateAt(index).fragment());
        }
        return List.copyOf(variants);
    }

    private static String diagnostic(BlockDescriptor descriptor, List<Property<?>> properties, long product) {
        StringBuilder terms = new StringBuilder();
        for (int i = 0; i < properties.size(); i++) {
            Property<?> property = properties.get(i);
            if (i > 0) {
                terms.append(" × ");
            }
            terms.append(property.name()).append('(').append(property.values().size()).append(')');
        }
        return "block " + descriptor.id() + " declares " + properties.size() + " propert"
            + (properties.size() == 1 ? "y" : "ies") + " with a state product of " + product
            + " states, over the " + STATE_BUDGET + "-state budget (terms: " + terms
            + "). Reduce a property's declared values or split the block; the engine never trims a state model.";
    }
}
