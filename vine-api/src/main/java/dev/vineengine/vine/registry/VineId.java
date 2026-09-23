package dev.vineengine.vine.registry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Namespaced engine content ID ({@code "namespace:path"}) — the single identity
 * type for every piece of VINE content on every cell (sub-02 §2).
 *
 * <p>Everything addressable — descriptors, registry types, inter-consumer
 * references — is a {@code VineId}, never a bare string, so ids stay valid and
 * namespaced from authoring through cooked assets. Character rules follow the
 * post-1.13 flattened model (plan §5.2): namespace
 * {@code [a-z0-9_.-]}, path {@code [a-z0-9_./-]}; anything else is rejected at
 * construction, never downstream.
 *
 * <p><b>Invariants:</b> immutable; {@link #parse} and {@link #toString} are exact
 * inverses; {@link #compareTo} orders by namespace then path, which is the
 * canonical sort for registry maps and deterministic cooked output.
 */
public record VineId(String namespace, String path) implements Comparable<VineId> {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9_./-]+");

    public VineId {
        validate("namespace", namespace, NAMESPACE_PATTERN);
        validate("path", path, PATH_PATTERN);
    }

    /** Validated factory; equivalent to the canonical constructor. */
    public static VineId of(String namespace, String path) {
        return new VineId(namespace, path);
    }

    /**
     * Parses the canonical {@code "namespace:path"} form. Exactly one separator
     * with non-empty halves is required — a missing namespace is a bug, not a
     * default, so no implicit namespace is ever assumed.
     */
    public static VineId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':') || separator == value.length() - 1) {
            throw new IllegalArgumentException(
                "VineId must be exactly \"namespace:path\": \"" + value + "\"");
        }
        return new VineId(value.substring(0, separator), value.substring(separator + 1));
    }

    private static void validate(String component, String value, Pattern pattern) {
        Objects.requireNonNull(value, component);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(
                "VineId " + component + " must match " + pattern.pattern() + ": \"" + value + "\"");
        }
    }

    /** Lexicographic by namespace, then path. */
    @Override
    public int compareTo(VineId other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : path.compareTo(other.path);
    }

    /** Canonical {@code "namespace:path"} form; {@link #parse} round-trips it exactly. */
    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}
