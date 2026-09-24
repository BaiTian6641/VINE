package dev.vineengine.vine.internal.driver1211.neoforge.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The NeoForge carrier for one engine enum axis (sub-07 Stage B): a vanilla
 * {@link net.minecraft.world.level.block.state.properties.Property} whose values
 * are the descriptor's declared enum constants, in declaration order.
 *
 * <p><b>Why not vanilla's {@code EnumProperty}:</b> it is bound to
 * {@code T extends Enum<T> & StringRepresentable}, and an engine-owned enum can
 * never satisfy that — {@code StringRepresentable} is a game interface, and the
 * enum type a descriptor names is shared by every cell (a headless runtime has
 * no Minecraft class to implement it with). This carrier keeps the vanilla
 * contract that matters (a native property with the declared value set, in the
 * declared order, carrying the property's own value codec) and supplies the
 * naming the native state grammar demands.
 *
 * <p><b>Naming, and why it is not the engine's:</b> a native state definition
 * only accepts lowercase value names ({@code [a-z0-9_]+}), while an engine enum's
 * names are Java constant names ({@code IDLE}), and the engine's own state string
 * pins them ({@code mode=IDLE}). The native name is therefore the lowercased
 * constant name — the same convention {@code StringRepresentable} users follow —
 * and the mapping back to the engine value goes through the declared constant
 * itself, never through a name. The engine's state identity stays exactly what
 * the descriptor declared, value order included (the engine's flattening order).
 * A constant whose name cannot be spoken natively is an authoring error and is
 * rejected here, naming the property, the constant and the offending name.
 *
 * <p><b>Invariants:</b> the values are the declared constants in declared order;
 * native names are unique and grammar-legal; the first value is the native
 * default, which is the engine's declared default too. Immutable.
 *
 * @param <E> the engine's enum type, as declared by the descriptor's property
 */
public final class EngineEnumProperty<E extends Enum<E>>
        extends net.minecraft.world.level.block.state.properties.Property<E> {

    /** The native value-name grammar, mirrored from the engine's own property rule. */
    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");

    private final List<E> values;
    private final Map<String, E> byNativeName;

    private EngineEnumProperty(String name, Class<E> type, List<E> declared) {
        super(name, type);
        List<E> copy = List.copyOf(declared);
        Map<String, E> names = new LinkedHashMap<>(copy.size());
        for (E value : copy) {
            String nativeName = nativeName(value);
            if (!NAME.matcher(nativeName).matches()) {
                throw new IllegalArgumentException("EngineEnumProperty " + name + ": " + value.getDeclaringClass().getName()
                    + " constant " + value + " has no native name (a native state value must match " + NAME
                    + ", got '" + nativeName + "') — rename the constant or declare a different axis");
            }
            E previous = names.put(nativeName, value);
            if (previous != null) {
                throw new IllegalArgumentException("EngineEnumProperty " + name + ": " + value.getDeclaringClass().getName()
                    + " constants " + previous + " and " + value + " share the native name '" + nativeName
                    + "' — the native state could not tell them apart");
            }
        }
        this.values = copy;
        this.byNativeName = Map.copyOf(names);
    }

    /**
     * The native carrier for {@code declared} values of {@code type}, in declared
     * order — the one place an engine enum axis becomes a native property.
     */
    public static <E extends Enum<E>> EngineEnumProperty<E> create(String name, Class<E> type, List<E> declared) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(declared, "declared");
        return new EngineEnumProperty<>(name, type, declared);
    }

    /**
     * The declared values, in declared order — a list, not a set, because the
     * declared order <em>is</em> the engine's flattening order (vanilla's
     * {@code EnumProperty} hands back an unordered set; a driver may not).
     */
    @Override
    public List<E> getPossibleValues() {
        return values;
    }

    /** The native name of {@code value}: its lowercased constant name. */
    @Override
    public String getName(E value) {
        return nativeName(value);
    }

    @Override
    public Optional<E> getValue(String nativeName) {
        return Optional.ofNullable(byNativeName.get(nativeName));
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof EngineEnumProperty<?> property && super.equals(other)
            && values.equals(property.values);
    }

    @Override
    public int generateHashCode() {
        return 31 * super.generateHashCode() + values.hashCode();
    }

    private static String nativeName(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
