package dev.vineengine.vine.internal.command;

import java.util.ArrayList;
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
            for (CommandDescriptor descriptor : descriptors.values()) {
                CommandDescriptor first = byRootLiteral.putIfAbsent(descriptor.root().name(), descriptor);
                if (first != null) {
                    LOG.log(System.Logger.Level.WARNING,
                        "[VINE] command conflict: root literal '" + descriptor.root().name() + "' claimed by both "
                            + first.id() + " and " + descriptor.id()
                            + " — first-registered wins (sub-06 §2 merge policy)");
                }
            }
            resolved = List.copyOf(byRootLiteral.values());
        }
        return resolved;
    }
}
