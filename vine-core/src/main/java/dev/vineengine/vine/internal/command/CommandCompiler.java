package dev.vineengine.vine.internal.command;

import java.util.HashSet;
import java.util.Set;

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
        if (node instanceof CommandDescriptor.Argument argument
            && argument.type() != VineArgumentTypes.STRING) {
            throw new IllegalArgumentException("command '" + descriptor.id() + "': argument type '"
                + argument.type().id() + "' at " + path
                + " is not supported at Stage A — VineArgumentTypes.STRING only;"
                + " the remaining vanilla mirrors land with sub-06 Stage B, engine types with Stage C");
        }
        Set<String> siblingNames = new HashSet<>();
        for (CommandDescriptor.Node child : node.children()) {
            if (!siblingNames.add(child.name())) {
                throw new IllegalArgumentException("command '" + descriptor.id()
                    + "': duplicate sibling '" + child.name() + "' at " + path
                    + " — Brigadier child lookup is by name, so a duplicate can never be reached");
            }
            validateNode(descriptor, child, path + "/" + child.name());
        }
    }
}
