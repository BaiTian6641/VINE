package dev.vineengine.vine.internal.command;

import java.util.HashSet;
import java.util.Set;

import dev.vineengine.vine.command.ArgumentTypeRef;
import dev.vineengine.vine.command.CommandDescriptor;
import dev.vineengine.vine.command.VineArgumentTypes;

/**
 * The Stage A descriptor compiler (sub-06 §2): validates a descriptor against
 * the structural rules and the current stage's supported subset, at
 * registration time — an invalid descriptor fails at compile (registration),
 * never at dispatch.
 *
 * <p>Descriptors are data and are NOT transformed into Brigadier trees here:
 * with no Brigadier on the shared-module classpath, the M0 compiler validates
 * and hands the data tree to the drivers, whose translation into the native
 * registration hooks is near-mechanical (sub-06 §2). The pre-built-nodes
 * variant of the compiler arrives with the {@code CommandDriver} SPI contract.
 */
final class CommandCompiler {

    private CommandCompiler() {
    }

    /**
     * Validates the whole descriptor tree.
     *
     * @throws IllegalArgumentException on any structural or stage-subset violation
     */
    static void validate(CommandDescriptor descriptor) {
        validateNode(descriptor, descriptor.root(), "/" + descriptor.root().name());
    }

    private static void validateNode(CommandDescriptor descriptor, CommandDescriptor.Node node, String path) {
        if (node instanceof CommandDescriptor.Argument argument && !isSupportedType(argument.type())) {
            throw new IllegalArgumentException("command '" + descriptor.id() + "': argument type '"
                + argument.type().id() + "' at " + path
                + " is not supported — the vanilla mirrors (string/int/long/double/bool/greedy/enum) are "
                + "Stage B, the engine types (VINE_ID/VOXEL_PATH/PLAYER_IN_SESSION) land with Stage C");
        }
        if (node instanceof CommandDescriptor.Literal literal && literal.redirect() != null) {
            // An alias forwards the whole remainder: local children and a local
            // executor could never run, so their presence is a descriptor bug,
            // not a runtime surprise.
            if (!literal.children().isEmpty()) {
                throw new IllegalArgumentException("command '" + descriptor.id() + "': redirect node '"
                    + path + "' must not declare children — the target tree parses the rest of the line");
            }
            if (literal.executor() != null) {
                throw new IllegalArgumentException("command '" + descriptor.id() + "': redirect node '"
                    + path + "' must not declare an executor — the target tree's nodes execute");
            }
        }
        Set<String> siblingNames = new HashSet<>();
        for (int i = 0; i < node.children().size(); i++) {
            CommandDescriptor.Node child = node.children().get(i);
            if (child instanceof CommandDescriptor.Argument greedy && greedy.type() == VineArgumentTypes.GREEDY) {
                // A greedy argument consumes the rest of the line: it can never
                // have children, and a sibling declared after it is unreachable
                // (Brigadier resolves the greedy postfix first). Reject at
                // registration, not at dispatch.
                if (!greedy.children().isEmpty()) {
                    throw new IllegalArgumentException("command '" + descriptor.id()
                        + "': greedy argument '" + path + "/" + greedy.name()
                        + "' must not declare children — it captures the rest of the line");
                }
                if (i != node.children().size() - 1) {
                    throw new IllegalArgumentException("command '" + descriptor.id()
                        + "': greedy argument '" + path + "/" + greedy.name()
                        + "' must be the last child of '" + path + "' — siblings declared after it are"
                        + " unreachable (the greedy postfix consumes the line)");
                }
            }
            if (!siblingNames.add(child.name())) {
                throw new IllegalArgumentException("command '" + descriptor.id()
                    + "': duplicate sibling '" + child.name() + "' at " + path
                    + " — Brigadier child lookup is by name, so a duplicate can never be reached");
            }
            validateNode(descriptor, child, path + "/" + child.name());
        }
    }

    /**
     * Whether the current stage can map this argument type. Engine types
     * (sub-06 Stage C) fail here — explicitly at registration, never at
     * dispatch.
     */
    static boolean isSupportedType(ArgumentTypeRef<?> type) {
        String id = type.id();
        return id.equals("string") || id.equals("int") || id.equals("long") || id.equals("double")
            || id.equals("bool") || id.equals("greedy") || id.startsWith("enum:")
            // sub-06 Stage C engine types: server-parsed, mapped to the nearest
            // vanilla type in the synced tree.
            || id.equals("vine_id") || id.equals("voxel_path") || id.equals("player_in_session");
    }
}
