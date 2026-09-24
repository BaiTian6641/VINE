package dev.vineengine.vine.content;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One flattened block-state property (sub-07 Stage B, plan §5.3): a named axis
 * whose declared values multiply into its descriptor's state table. The shape is
 * deliberately the pair every post-1.13 cell exposes — boolean, bounded integer,
 * enum — so a cell materializes a property 1:1 without branching on loader, and
 * the engine's state model is the only model on every cell (no legacy metadata
 * path exists to reconcile).
 *
 * <p><b>Canonical factories:</b> {@link #bool(String)}, {@link #intRange(String, int, int)},
 * {@link #ofEnum(String, Class)}. Names and values are validated here, at
 * authoring time, because a malformed property can never be materialized by a
 * cell: the failure belongs to the descriptor's author, not to a boot.
 *
 * <p><b>Invariants:</b> the name matches the native property grammar
 * ({@code [a-z0-9_]+}); the value list is non-empty, duplicate-free and — for
 * integers — strictly ascending and inside the vanilla bound {@code 0..15}, so
 * every cell can carry it; enum values keep their declaration order (a subset is
 * legal, a reordering is not: it would silently change the flattened state
 * identity). Immutable; equality is identity-of-fields, so two descriptors that
 * declare the same axis are interchangeable in JSON and in fixtures.
 *
 * @param name   the property's name on every cell; also the key inside the
 *               engine's state-string form ({@code facing=north,lit=true})
 * @param values the declared values, in flattening order; the first value is
 *               the default a cell materializes for a fresh descriptor state
 * @param type   the value class, used by cells to pick the native property kind
 *               ({@code Boolean} → boolean, {@code Integer} → bounded int,
 *               enum → enum)
 * @param <T>    the value type; bounded by {@link Comparable} so native
 *               properties and engine states share one ordering contract
 */
public record Property<T extends Comparable<T>>(String name, List<T> values, Class<T> type) {

    /** The native property-name grammar every cell enforces (post-1.13). */
    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[a-z0-9_]+");

    /**
     * Single source of truth for every representation of this data (sub-02 §2).
     * The wire/JSON form names the value kind explicitly so decoding never has to
     * guess: {@code {"name":"lit","kind":"bool"}}, {@code {"name":"level","kind":"int",
     * "values":[0,1,2]}}, {@code {"name":"facing","kind":"enum","enumClass":"…","values":["north","south"]}}.
     */
    private static final Codec<Raw> RAW = Raw.CODEC;

    public static final Codec<Property<?>> CODEC = RAW.flatXmap(
        raw -> {
            // The record's own validation is the parse rule; its message is the
            // diagnostic an author sees (sub-02 §2 keeps one validation path per
            // data type), surfaced as a DataResult rather than a thrown exception.
            try {
                return DataResult.success(decode(raw.name(), raw.kind(), raw.enumClass(), raw.values()));
            } catch (RuntimeException e) {
                return DataResult.error(e::getMessage);
            }
        },
        property -> DataResult.success(new Raw(property.name(), property.kind(), property.enumClassName(),
            property.encodedValues())));

    /** The flat JSON shape; only {@link #CODEC} turns it into a validated property. */
    private record Raw(String name, String kind, String enumClass, List<String> values) {

        private static final Codec<Raw> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("name").forGetter(Raw::name),
            Codec.STRING.fieldOf("kind").forGetter(Raw::kind),
            Codec.STRING.optionalFieldOf("enumClass", "").forGetter(Raw::enumClass),
            Codec.STRING.listOf().fieldOf("values").forGetter(Raw::values)
        ).apply(instance, Raw::new));
    }

    /** A two-valued boolean axis — the native boolean property on every cell. */
    public static Property<Boolean> bool(String name) {
        return new Property<>(name, List.of(Boolean.TRUE, Boolean.FALSE), Boolean.class);
    }

    /**
     * A bounded integer axis with one value per integer in {@code [min, max]}.
     *
     * <p>Bounds are the vanilla ones ({@code 0..15}) on purpose: a wider range
     * has no native carrier on any supported cell, and silently trimming it
     * would change the state identity the author declared.
     */
    public static Property<Integer> intRange(String name, int min, int max) {
        if (min > max) {
            throw new IllegalArgumentException("Property.intRange(" + name + "): min " + min + " > max " + max);
        }
        if (min < 0 || max > 15) {
            throw new IllegalArgumentException("Property.intRange(" + name + "): values must be inside 0..15 (the"
                + " native bounded-int carrier), got " + min + ".." + max);
        }
        List<Integer> values = new ArrayList<>(max - min + 1);
        for (int value = min; value <= max; value++) {
            values.add(value);
        }
        return new Property<>(name, List.copyOf(values), Integer.class);
    }

    /**
     * An enum axis carrying {@code type}'s constants in declaration order. The
     * full constant set is the canonical form; a subset in declaration order is
     * legal and is what a cell materializes natively (vanilla enum properties
     * accept subsets), a reordering is not.
     */
    public static <E extends Enum<E>> Property<E> ofEnum(String name, Class<E> type) {
        Objects.requireNonNull(type, "type");
        E[] constants = type.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalArgumentException("Property.ofEnum(" + name + "): " + type.getName()
                + " is not an enum with constants");
        }
        return new Property<>(name, List.of(constants), type);
    }

    public Property {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(type, "type");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Property.name must match [a-z0-9_]+ on every cell: " + name);
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Property " + name + ": at least one value is required"
                + " (a property with no values has no state table)");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Property " + name + ": values must not contain null");
        }
        if (new java.util.HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException("Property " + name + ": duplicate values " + values);
        }
        if (type == Integer.class) {
            ensureAscendingInts(name, values);
        } else if (type == Boolean.class) {
            ensureBooleans(name, values);
        } else if (type.isEnum()) {
            ensureDeclarationOrder(name, type, values);
        }
    }

    /** The value kind a cell picks its native carrier from. */
    public String kind() {
        if (type == Boolean.class) {
            return "bool";
        }
        if (type == Integer.class) {
            return "int";
        }
        return "enum";
    }

    /** The enum class name for the JSON form; empty for the two built-in kinds. */
    public String enumClassName() {
        return type.isEnum() ? type.getName() : "";
    }

    /** The declared values as strings — the JSON/state-string encoding. */
    public List<String> encodedValues() {
        return values.stream().map(String::valueOf).toList();
    }

    /**
     * The canonical engine state-string fragment for this property:
     * {@code name=value}.
     *
     * <p>The parameter is untyped so a state walking its erased property list can
     * render itself without a cast; the value is still checked against the
     * declared list, so a fragment can never name a value the property does not
     * have.
     *
     * @throws IllegalArgumentException if {@code value} is not one this property
     *         declares
     */
    public String fragment(Comparable<?> value) {
        if (!values.contains(value)) {
            throw new IllegalArgumentException("Property " + name + ": value " + value + " is not declared (declared: "
                + encodedValues() + ")");
        }
        return name + "=" + value;
    }

    /**
     * The value parsed from its state-string form; legal only for values this
     * property declares — anything else is the caller's bug, reported as such.
     */
    @SuppressWarnings("unchecked")
    public T parse(String encoded) {
        for (T value : values) {
            if (String.valueOf(value).equals(encoded)) {
                return value;
            }
        }
        throw new IllegalArgumentException("Property " + name + ": value '" + encoded + "' is not declared (declared: "
            + encodedValues() + ")");
    }

    private static void ensureBooleans(String name, List<?> values) {
        for (Object value : values) {
            if (!(value instanceof Boolean)) {
                throw new IllegalArgumentException("Property " + name + " declares Boolean type but holds " + value);
            }
        }
    }

    private static void ensureAscendingInts(String name, List<?> values) {
        int previous = Integer.MIN_VALUE;
        for (Object value : values) {
            if (!(value instanceof Integer current)) {
                throw new IllegalArgumentException("Property " + name + " declares Integer type but holds " + value);
            }
            if (current <= previous) {
                throw new IllegalArgumentException("Property " + name + ": integer values must be strictly ascending,"
                    + " got " + values);
            }
            if (current < 0 || current > 15) {
                throw new IllegalArgumentException("Property " + name + ": integer values must be inside 0..15, got "
                    + current);
            }
            previous = current;
        }
    }

    private static void ensureDeclarationOrder(String name, Class<?> type, List<?> values) {
        Enum<?>[] constants = (Enum<?>[]) type.getEnumConstants();
        int cursor = -1;
        for (Object value : values) {
            if (!type.isInstance(value)) {
                throw new IllegalArgumentException("Property " + name + " declares " + type.getName() + " but holds "
                    + value);
            }
            int ordinal = ((Enum<?>) value).ordinal();
            if (ordinal <= cursor) {
                throw new IllegalArgumentException("Property " + name + ": enum values must follow declaration order of "
                    + type.getName() + ", got " + values);
            }
            cursor = ordinal;
        }
    }

    @SuppressWarnings("unchecked")
    private static Property<?> decode(String name, String kind, String enumClass, List<String> encoded) {
        return switch (kind) {
            case "bool" -> parseBool(name, encoded);
            case "int" -> parseInt(name, encoded);
            case "enum" -> parseEnum(name, enumClass, encoded);
            default -> throw new IllegalArgumentException("Property " + name + ": unknown kind '" + kind
                + "' (expected bool, int or enum)");
        };
    }

    private static Property<Boolean> parseBool(String name, List<String> encoded) {
        List<Boolean> values = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            if (!"true".equals(value) && !"false".equals(value)) {
                throw new IllegalArgumentException("Property " + name + ": boolean value must be true or false, got '"
                    + value + "'");
            }
            values.add(Boolean.valueOf(value));
        }
        return new Property<>(name, List.copyOf(values), Boolean.class);
    }

    private static Property<Integer> parseInt(String name, List<String> encoded) {
        List<Integer> values = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            try {
                values.add(Integer.valueOf(value));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Property " + name + ": integer value expected, got '" + value + "'");
            }
        }
        return new Property<>(name, List.copyOf(values), Integer.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Property<?> parseEnum(String name, String enumClass, List<String> encoded) {
        if (enumClass.isEmpty()) {
            throw new IllegalArgumentException("Property " + name + ": an enum property must name its enumClass");
        }
        Class<?> raw;
        try {
            raw = Class.forName(enumClass, false, Thread.currentThread().getContextClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("Property " + name + ": enumClass " + enumClass
                + " is not on this classpath", e);
        }
        if (!raw.isEnum()) {
            throw new IllegalArgumentException("Property " + name + ": " + enumClass + " is not an enum");
        }
        Class<? extends Enum> enumType = raw.asSubclass(Enum.class);
        List values = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            try {
                values.add(Enum.valueOf(enumType, value));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Property " + name + ": " + enumClass + " has no constant '"
                    + value + "'", e);
            }
        }
        return new Property(name, List.copyOf(values), enumType);
    }
}
