package dev.vineengine.vine.internal.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
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
            List<CommandDescriptor> merged = List.copyOf(byRootLiteral.values());
            Set<String> roots = new LinkedHashSet<>();
            for (CommandDescriptor descriptor : merged) {
                roots.add(descriptor.root().name());
            }
            resolved = resolveRedirects(merged, roots);
        }
        return resolved;
    }

    /**
     * Resolves every redirect against the merged root set: an alias whose target
     * is missing (or ends in a cycle) is reported and degrades to a plain
     * literal, so the dispatcher build never depends on consumer ordering.
     */
    private List<CommandDescriptor> resolveRedirects(List<CommandDescriptor> merged, Set<String> roots) {
        // Registration order (not hash order): the conflict/alias reports a
        // scenario asserts on must be deterministic across runs and cells.
        Map<String, String> redirects = new LinkedHashMap<>();
        for (CommandDescriptor descriptor : merged) {
            // A redirect lives either on the root itself (the alias root *is* the
            // alias: /vt forwards to /vine_test) or on a child literal of the
            // root's tree.
            if (descriptor.root().redirect() != null) {
                redirects.put(descriptor.root().name(), descriptor.root().redirect());
            }
            for (CommandDescriptor.Node node : descriptor.root().children()) {
                if (node instanceof CommandDescriptor.Literal literal && literal.redirect() != null) {
                    redirects.put(literal.name(), literal.redirect());
                }
            }
        }
        Set<String> dead = new HashSet<>();
        for (Map.Entry<String, String> entry : redirects.entrySet()) {
            if (dead.contains(entry.getKey())) {
                // Already reported as part of a cycle it belongs to.
                continue;
            }
            if (!roots.contains(entry.getValue())) {
                LOG.log(System.Logger.Level.WARNING,
                    "[VINE] command alias '/" + entry.getKey() + "' targets unregistered root literal '"
                        + entry.getValue() + "' — alias dropped, build continues");
                dead.add(entry.getKey());
                continue;
            }
            // Cycle check: walk the chain of aliases (only aliases chain; a real
            // root ends it).
            Set<String> seen = new LinkedHashSet<>();
            String at = entry.getKey();
            while (redirects.containsKey(at)) {
                if (!seen.add(at)) {
                    LOG.log(System.Logger.Level.WARNING,
                        "[VINE] command alias cycle: " + String.join(" -> ", seen) + " -> " + at
                            + " — every alias in the cycle is dropped, build continues");
                    dead.addAll(seen);
                    break;
                }
                at = redirects.get(at);
            }
        }
        if (dead.isEmpty()) {
            return merged;
        }
        List<CommandDescriptor> out = new ArrayList<>(merged.size());
        for (CommandDescriptor descriptor : merged) {
            List<CommandDescriptor.Node> children = new ArrayList<>();
            for (CommandDescriptor.Node node : descriptor.root().children()) {
                if (node instanceof CommandDescriptor.Literal literal && dead.contains(literal.name())) {
                    children.add(new CommandDescriptor.Literal(literal.name(), literal.permission(),
                        literal.executor(), literal.children(), literal.requirements(), null));
                } else {
                    children.add(node);
                }
            }
            CommandDescriptor.Literal root = descriptor.root();
            out.add(new CommandDescriptor(descriptor.id(), new CommandDescriptor.Literal(root.name(),
                root.permission(), root.executor(), children, root.requirements(),
                dead.contains(root.name()) ? null : root.redirect())));
        }
        return List.copyOf(out);
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
        String redirect = literal.redirect();
        if (redirect != null && children.size() > literal.children().size()) {
            // A later consumer attached children to a redirect node — the
            // redirect would make them unreachable, so the children win and the
            // alias is reported as dropped.
            conflict(path, literal, second, firstId, secondId);
            redirect = null;
        }
        return new CommandDescriptor.Literal(literal.name(), literal.permission(), literal.executor(),
            List.copyOf(children.values()), literal.requirements(), redirect);
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
