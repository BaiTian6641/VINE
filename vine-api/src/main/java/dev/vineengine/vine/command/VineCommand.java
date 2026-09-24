package dev.vineengine.vine.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.registry.VineId;

/**
 * Builder for {@link CommandDescriptor}s (sub-06 §2). Produces pure-data
 * descriptors — nothing is registered until the built descriptor is handed to
 * {@link VineCommands#register(CommandDescriptor)}.
 *
 * <p>Stage A subset: {@code literal}, {@code argument} with
 * {@link VineArgumentTypes#STRING}, {@code executes}, and
 * {@link VinePermission.Level} gates. Usage:
 *
 * <pre>{@code
 * CommandDescriptor cmd = VineCommand.literal("vine_test")
 *     .permission(VinePermission.level(2))
 *     .then(VineCommand.literal("echo")
 *         .then(VineCommand.argument("msg", VineArgumentTypes.STRING)
 *             .executes(ctx -> {
 *                 ctx.feedback("echo: " + ctx.argument("msg", String.class));
 *                 return 1;
 *             })))
 *     .build(VineId.of("vine_test", "vine_test"));
 * }</pre>
 *
 * <p>Builders are single-use authoring objects: not thread-safe, and a builder
 * handed to {@link Builder#then(Builder)} must not be reused elsewhere (one
 * node, one parent — Brigadier tree semantics).
 */
public final class VineCommand {

    private VineCommand() {
    }

    /** Starts a literal node. The descriptor root is always a literal. */
    public static Builder literal(String name) {
        return new Builder(name, null);
    }

    /** Starts an argument node of the given engine argument type. */
    public static <T> Builder argument(String name, ArgumentTypeRef<T> type) {
        return new Builder(name, Objects.requireNonNull(type, "type"));
    }

    /** One node under construction; see {@link VineCommand} for usage. */
    public static final class Builder {

        private final String name;
        private final ArgumentTypeRef<?> type;
        private final List<Builder> children = new ArrayList<>();
        private VinePermission permission;
        private VineCommandExecutor executor;
        private final List<java.util.function.Predicate<CommandSourceRef>> requirements = new ArrayList<>();
        private SuggestionSource suggestions;
        private String redirect;

        private Builder(String name, ArgumentTypeRef<?> type) {
            // Reject-at-construction (same convention as VineId): an invalid node
            // name fails at the builder call, never downstream at build/dispatch.
            this.name = CommandDescriptor.checkName(type == null ? "literal" : "argument", name);
            this.type = type;
        }

        /** Gates this node behind a permission (evaluated before the node is usable). */
        public Builder permission(VinePermission permission) {
            this.permission = Objects.requireNonNull(permission, "permission");
            return this;
        }
        /**
         * Forwards the rest of the line to another registered root literal's tree
         * (sub-06 Stage B) — an alias, {@code /vt echo hi} behaves exactly as
         * {@code /vine_test echo hi}, including permissions and suggestions.
         *
         * <p>Literal nodes only, and the node must stay childless and
         * executor-free: everything after the alias name is parsed by the target
         * tree, so local children could never be reached. The target is resolved
         * across all registered descriptors when the engine resolves the merged
         * snapshot; an unknown target or a redirect cycle is reported and this
         * node degrades to a plain literal (never a dispatcher failure).
         */
        public Builder redirect(String targetRootLiteral) {
            if (type != null) {
                throw new IllegalStateException(
                    "redirect() applies to literal nodes — argument '" + name + "' cannot alias a tree");
            }
            this.redirect = Objects.requireNonNull(targetRootLiteral, "targetRootLiteral");
            return this;
        }

        /**
         * Adds an extra gate (sub-06 Stage B): evaluated server-side after the
         * permission check, before this node's executor. A {@code false} denies
         * with an error feedback and never runs the executor.
         */
        public Builder requires(java.util.function.Predicate<CommandSourceRef> requirement) {
            requirements.add(Objects.requireNonNull(requirement, "requirement"));
            return this;
        }

        /**
         * Attaches completion candidates (argument nodes only; sub-06 Stage B).
         * The engine computes them server-side, so vanilla clients tab-complete
         * without a client mod.
         */
        public Builder suggests(SuggestionSource source) {
            if (type == null) {
                throw new IllegalStateException(
                    "suggests() applies to argument nodes — literal '" + name + "' has nothing to complete");
            }
            this.suggestions = Objects.requireNonNull(source, "source");
            return this;
        }

        /** Appends a child node. */
        public Builder then(Builder child) {
            children.add(Objects.requireNonNull(child, "child"));
            return this;
        }

        /** Attaches the behavior run when the command terminates at this node. */
        public Builder executes(VineCommandExecutor executor) {
            this.executor = Objects.requireNonNull(executor, "executor");
            return this;
        }

        /**
         * Builds the descriptor. Root-only: the node must be a literal, and the
         * id is the descriptor's registration identity (duplicate ids are
         * rejected at registration).
         *
         * @throws IllegalStateException if called on an argument node
         */
        public CommandDescriptor build(VineId id) {
            if (type != null) {
                throw new IllegalStateException(
                    "build() is root-only and the descriptor root must be a literal — argument node '" + name + "'");
            }
            return new CommandDescriptor(Objects.requireNonNull(id, "id"),
                (CommandDescriptor.Literal) buildNode());
        }

        private CommandDescriptor.Node buildNode() {
            List<CommandDescriptor.Node> built = new ArrayList<>(children.size());
            for (Builder child : children) {
                built.add(child.buildNode());
            }
            if (type != null) {
                return new CommandDescriptor.Argument(name, type, permission, executor, built,
                    requirements, suggestions);
            }
            return new CommandDescriptor.Literal(name, permission, executor, built, requirements, redirect);
        }
    }
}
