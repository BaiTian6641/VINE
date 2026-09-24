package dev.vineengine.vine.internal.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.vineengine.vine.command.CommandDescriptor;
import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.registry.VineId;

/**
 * The engine's command registry (sub-06 §2): holds registered descriptors in
 * registration order, freezes when the engine enters {@code REGISTRIES_FROZEN}
 * (wired beside the {@code DescriptorStore} freeze in the engine bootstrap), and
 * hands drivers a resolved snapshot per native dispatcher build.
 *
 * <p>Registration is effectively single-threaded (loader init); methods are
 * synchronized so the freeze transition cannot race a write — same discipline
 * as {@code DescriptorStore}.
 *
 * <p><b>Merge policy (sub-06 §2):</b> snapshot resolution groups descriptors by
 * root literal; a root claimed by two descriptors resolves first-registered-wins
 * and logs a conflict report naming both ids. Resolution is computed once per
 * change and cached, so repeated dispatcher builds ({@code /reload}) never
 * re-log.
 */
public final class CommandService implements VineCommands {

    private static final System.Logger LOG = System.getLogger(CommandBridge.LOG_NAME);

    private final Map<VineId, CommandDescriptor> descriptors = new LinkedHashMap<>();
    private boolean frozen;
    private List<CommandDescriptor> resolved;

    /**
     * Registers one descriptor after engine compilation ({@link CommandCompiler}).
     * Duplicate ids and post-freeze writes throw — a conflicting or late
     * registration is a bug, never a guess.
     */
    @Override
    public synchronized void register(CommandDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (frozen) {
            throw new IllegalStateException("command '" + descriptor.id()
                + "' registered after REGISTRIES_FROZEN — command registration closes at registry freeze (sub-06 §2)");
        }
        CommandCompiler.validate(descriptor);
        CommandDescriptor previous = descriptors.putIfAbsent(descriptor.id(), descriptor);
        if (previous != null) {
            throw new IllegalStateException("duplicate command descriptor id '" + descriptor.id() + "'");
        }
        resolved = null;
    }

    /** Freezes registration. Idempotent; snapshots stay available. */
    public synchronized void freeze() {
        frozen = true;
    }

    /**
     * The conflict-resolved descriptor set for one native dispatcher build.
     * Re-invoked per build (drivers re-attach on every dispatcher construction);
     * Java-registered descriptors are static across builds (sub-06 §2 reload
     * policy), so the result is stable post-freeze.
     */
    public synchronized List<CommandDescriptor> snapshotForNativePass() {
        if (resolved == null) {
            Map<String, CommandDescriptor> byRootLiteral = new LinkedHashMap<>();
            Map<String, VineId> claimerOfRoot = new HashMap<>();
            for (CommandDescriptor descriptor : descriptors.values()) {
                String root = descriptor.root().name();
                CommandDescriptor first = byRootLiteral.get(root);
                if (first == null) {
                    byRootLiteral.put(root, descriptor);
                    claimerOfRoot.put(root, descriptor.id());
                    continue;
                }
                // Merge policy (sub-06 §2): same root literal merges children;
                // a conflicting argument shape keeps the first and reports both
                // ids. Build continues either way.
                CommandDescriptor.Literal merged = (CommandDescriptor.Literal) merge(
                    first.root(), descriptor.root(), root, claimerOfRoot.get(root), descriptor.id());
                byRootLiteral.put(root, new CommandDescriptor(first.id(), merged));
            }
            resolved = List.copyOf(byRootLiteral.values());
        }
        return resolved;
    }

    /** Merges two nodes by name: literals merge recursively, arguments keep the first of a shape clash. */
    private CommandDescriptor.Node merge(CommandDescriptor.Node first, CommandDescriptor.Node second, String path,
                                         VineId firstId, VineId secondId) {
        if (!first.getClass().equals(second.getClass())) {
            conflict(path, first, second, firstId, secondId);
            return first;
        }
        if (first instanceof CommandDescriptor.Argument a && second instanceof CommandDescriptor.Argument b
            && a.type() != b.type()) {
            conflict(path, first, second, firstId, secondId);
            return first;
        }
        // Same shape: recursive child merge, registration order preserved
        // (the later consumer's new children append after the first's).
        Map<String, CommandDescriptor.Node> children = new LinkedHashMap<>();
        for (CommandDescriptor.Node child : first.children()) {
            children.put(child.name(), child);
        }
        for (CommandDescriptor.Node child : second.children()) {
            CommandDescriptor.Node existing = children.get(child.name());
            children.put(child.name(), existing == null
                ? child
                : merge(existing, child, path + " > " + child.name(), firstId, secondId));
        }
        if (first instanceof CommandDescriptor.Argument a) {
            return new CommandDescriptor.Argument(a.name(), a.type(), a.permission(), a.executor(),
                List.copyOf(children.values()), a.requirements(), a.suggestions());
        }
        CommandDescriptor.Literal literal = (CommandDescriptor.Literal) first;
        return new CommandDescriptor.Literal(literal.name(), literal.permission(), literal.executor(),
            List.copyOf(children.values()), literal.requirements());
    }

    private void conflict(String path, CommandDescriptor.Node first, CommandDescriptor.Node second,
                          VineId firstId, VineId secondId) {
        LOG.log(System.Logger.Level.WARNING,
            "[VINE] command conflict: '" + path + "' claimed by both " + firstId + " (" + shape(first)
                + ") and " + secondId + " (" + shape(second) + ") — first-registered wins, build continues");
    }

    private static String shape(CommandDescriptor.Node node) {
        return node instanceof CommandDescriptor.Argument argument
            ? "argument " + argument.type().id()
            : "literal";
    }
}
