package dev.vineengine.vine.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.content.BlockDescriptor;
import dev.vineengine.vine.content.Property;
import dev.vineengine.vine.registry.VineId;

/**
 * One engine block state (sub-07 Stage B): a block id plus one value per declared
 * property, in the descriptor's flattening order. This is the engine's whole
 * state model — every supported cell is post-1.13, so there is no legacy
 * metadata form to reconcile and no second representation to keep in sync.
 *
 * <p><b>Self-describing on purpose:</b> the state carries its {@link #schema()}
 * alongside its {@link #values()} so typed access ({@link #value(Property)},
 * {@link #with(Property, Object)}) needs no registry lookup, and so a state that
 * was built against a different schema fails loudly instead of silently
 * addressing the wrong axis. The schema is the descriptor's list, so a state is
 * only ever as valid as the descriptor it came from.
 *
 * <p><b>Invariants:</b> {@code schema} and {@code values} have equal size; every
 * value is declared by its property; property names are unique; values are
 * immutable. Equality is identity-of-fields — a state is a value, never a live
 * handle to a block in a world.
 *
 * @param blockId the block this state belongs to
 * @param schema  the block's properties, in flattening order
 * @param values  one declared value per property, in the same order
 */
public record BlockState(VineId blockId, List<Property<?>> schema, List<Comparable<?>> values) {

    /**
     * The default state of {@code descriptor}: the first declared value of each
     * property. Identical to the default every cell materializes natively, so
     * "freshly placed block" means the same state engine-side and game-side.
     */
    public static BlockState defaultState(BlockDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        List<Property<?>> properties = descriptor.properties();
        List<Comparable<?>> values = new ArrayList<>(properties.size());
        for (Property<?> property : properties) {
            values.add(property.values().get(0));
        }
        return new BlockState(descriptor.id(), properties, List.copyOf(values));
    }

    public BlockState {
        Objects.requireNonNull(blockId, "blockId");
        schema = List.copyOf(Objects.requireNonNull(schema, "schema"));
        values = List.copyOf(Objects.requireNonNull(values, "values"));
        if (schema.size() != values.size()) {
            throw new IllegalArgumentException("BlockState " + blockId + ": " + schema.size() + " propert"
                + (schema.size() == 1 ? "y" : "ies") + " but " + values.size() + " value(s)");
        }
        java.util.HashSet<String> names = new java.util.HashSet<>(schema.size());
        for (int i = 0; i < schema.size(); i++) {
            Property<?> property = schema.get(i);
            Objects.requireNonNull(property, "schema element");
            if (!names.add(property.name())) {
                throw new IllegalArgumentException("BlockState " + blockId + ": duplicate property name '"
                    + property.name() + "'");
            }
            if (!property.values().contains(values.get(i))) {
                throw new IllegalArgumentException("BlockState " + blockId + ": property " + property.name()
                    + " does not declare value " + values.get(i) + " (declared: " + property.encodedValues() + ")");
            }
        }
    }

    /**
     * The value of {@code property} in this state.
     *
     * @throws IllegalArgumentException if this state's schema does not carry that
     *         property — a schema mismatch is the caller's bug, never a silent
     *         {@code null}
     */
    @SuppressWarnings("unchecked")
    public <T extends Comparable<T>> T value(Property<T> property) {
        Objects.requireNonNull(property, "property");
        int index = indexOfSchema(property.name());
        if (index < 0) {
            throw new IllegalArgumentException("BlockState " + blockId + " has no property '" + property.name()
                + "' (declared: " + schema.stream().map(Property::name).toList() + ")");
        }
        Property<?> declared = schema.get(index);
        if (!declared.equals(property)) {
            throw new IllegalArgumentException("BlockState " + blockId + ": property '" + property.name()
                + "' is declared as " + declared.encodedValues() + ", not " + property.encodedValues());
        }
        return (T) values.get(index);
    }

    /**
     * This state with {@code property} set to {@code value} — the engine's single
     * state-mutation primitive; the world applies it natively.
     *
     * @throws IllegalArgumentException if the property or the value is not part of
     *         this state's schema
     */
    public <T extends Comparable<T>> BlockState with(Property<T> property, T value) {
        Objects.requireNonNull(value, "value");
        value(property);
        if (!property.values().contains(value)) {
            throw new IllegalArgumentException("BlockState " + blockId + ": property '" + property.name()
                + "' does not declare value " + value + " (declared: " + property.encodedValues() + ")");
        }
        List<Comparable<?>> updated = new ArrayList<>(values);
        updated.set(indexOfSchema(property.name()), value);
        return new BlockState(blockId, schema, List.copyOf(updated));
    }

    /**
     * The canonical state string — {@code facing=north,lit=true}, properties in
     * flattening order. Deterministic by construction: this is what traces,
     * scenario assertions and fixture files compare, so the same state is never
     * spelled two ways.
     */
    public String fragment() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < schema.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(schema.get(i).fragment(values.get(i)));
        }
        return out.toString();
    }

    private int indexOfSchema(String name) {
        for (int i = 0; i < schema.size(); i++) {
            if (schema.get(i).name().equals(name)) {
                return i;
            }
        }
        return -1;
    }
}
