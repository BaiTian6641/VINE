package dev.vineengine.vine.command;

import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.registry.VineId;

/**
 * A command registration as pure data (sub-06 §2): a rooted tree of literal and
 * argument nodes, each optionally carrying a permission gate and an executor.
 * Produced by the {@link VineCommand} builder (Java path); the JSON descriptor
 * path (sub-02 machinery) lands with Stage B.
 *
 * <p><b>Invariants:</b> the root is always a {@link Literal} (Brigadier
 * dispatchers root at literals); node names are non-empty and contain no
 * whitespace (Brigadier parses literals/arguments up to the next space);
 * {@code children} lists are immutable. {@code permission} and {@code executor}
 * are nullable — an intermediate node typically carries neither.
 *
 * <p>Registration identity is the descriptor {@link #id()}; the root literal
 * name is the dispatcher-visible claim. Two descriptors claiming the same root
 * literal resolve deterministically — first-registered wins, both ids logged
 * (sub-06 §2 merge policy).
 */
public record CommandDescriptor(VineId id, Literal root) {

    public CommandDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(root, "root");
    }

    /** One node in the command tree. */
    public sealed interface Node {

        /** Literal text or argument name; non-empty, whitespace-free (checked at construction). */
        String name();

        /** Permission gate evaluated before this node is usable, or {@code null} for none. */
        VinePermission permission();

        /** Behavior when the command terminates at this node, or {@code null} for none. */
        VineCommandExecutor executor();

        /** Child nodes; immutable, registration order preserved. */
        List<Node> children();

        /**
         * Extra gates evaluated before this node's executor runs (sub-06 Stage B),
         * after the permission check. A predicate returning {@code false} denies
         * the command with an error feedback; predicates run server-side only.
         */
        List<java.util.function.Predicate<CommandSourceRef>> requirements();
    }

    /** A literal node — matches its {@link #name()} verbatim. */
    public record Literal(String name, VinePermission permission, VineCommandExecutor executor,
                          List<Node> children,
                          List<java.util.function.Predicate<CommandSourceRef>> requirements) implements Node {

        public Literal {
            checkName("literal", name);
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        }
    }

    /** An argument node — parses one token of {@link #type()} into a named value. */
    public record Argument(String name, ArgumentTypeRef<?> type, VinePermission permission,
                           VineCommandExecutor executor, List<Node> children,
                           List<java.util.function.Predicate<CommandSourceRef>> requirements,
                           SuggestionSource suggestions) implements Node {

        public Argument {
            checkName("argument", name);
            Objects.requireNonNull(type, "type");
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            requirements = List.copyOf(Objects.requireNonNull(requirements, "requirements"));
        }
    }

    static String checkName(String kind, String name) {
        Objects.requireNonNull(name, kind + " name");
        if (name.isEmpty()) {
            throw new IllegalArgumentException("command " + kind + " name must not be empty");
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                throw new IllegalArgumentException(
                    "command " + kind + " name must not contain whitespace (Brigadier parses to the next space): \""
                        + name + "\"");
            }
        }
        return name;
    }
}
