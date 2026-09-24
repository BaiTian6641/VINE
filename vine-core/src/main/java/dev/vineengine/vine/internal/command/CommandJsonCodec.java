package dev.vineengine.vine.internal.command;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.vineengine.vine.command.ArgumentTypeRef;
import dev.vineengine.vine.command.CommandDescriptor;
import dev.vineengine.vine.command.SuggestionSource;
import dev.vineengine.vine.command.VineArgumentTypes;
import dev.vineengine.vine.command.VineCommandExecutor;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.registry.VineId;

/**
 * The JSON authoring path for commands (sub-06 Stage B): one descriptor per
 * file, parsed into the same {@link CommandDescriptor} a Java builder produces,
 * so the compiler, merge policy, and dispatcher walker never learn that a
 * descriptor came from JSON.
 *
 * <p>Reference rules: a node names behavior by executor id, which the consumer
 * registered through {@code VineCommands.registerExecutor} — a lambda cannot
 * ride JSON, and resolving ids at load time keeps a typo a load failure rather
 * than a silent no-op. Facets that need code (requirement predicates, custom
 * suggestion sources) are Java-only by construction; JSON gets literal
 * candidate lists and op-level permissions.
 *
 * <pre>{@code
 * {
 *   "root": "vinequest",
 *   "permission": 2,
 *   "executor": "vine_test:json_root",
 *   "then": [
 *     { "literal": "start",
 *       "executor": "vine_test:json_start",
 *       "then": [ { "argument": "target", "type": "string",
 *                   "suggests": ["alpha", "beta"] } ] },
 *     { "literal": "alias", "redirect": "vine_test" }
 *   ]
 * }
 * }</pre>
 *
 * <p>Every failure names the file and the offending field: a malformed consumer
 * file is a load-time error, never a dispatcher surprise.
 */
final class CommandJsonCodec {

    private CommandJsonCodec() {
    }

    /** Parses one descriptor file. {@code source} is the file's display path (error messages, logs). */
    static CommandDescriptor parse(JsonObject json, String source, Function<VineId, VineCommandExecutor> executors) {
        String root = requiredString(json, "root", source);
        VinePermission permission = permission(json, source);
        VineCommandExecutor executor = executor(json, "executor", source, executors);
        List<CommandDescriptor.Node> children = children(json, source, executors, true);
        if (json.has("redirect")) {
            throw new IllegalArgumentException(source + ": the root node cannot be a redirect —"
                + " use a child literal, or make the alias file's root the alias itself");
        }
        return new CommandDescriptor(idOf(json, source), new CommandDescriptor.Literal(root, permission,
            executor, children, List.of(), null));
    }

    private static VineId idOf(JsonObject json, String source) {
        // The descriptor id defaults to a namespaced form of the file's root name;
        // files may declare one explicitly for stable conflict reports.
        if (json.has("id")) {
            return VineId.parse(requiredString(json, "id", source));
        }
        String root = requiredString(json, "root", source);
        int colon = root.indexOf(':');
        return colon > 0 ? VineId.parse(root) : VineId.of("vine_commands", root);
    }

    private static List<CommandDescriptor.Node> children(JsonObject json, String source,
                                                         Function<VineId, VineCommandExecutor> executors, boolean root) {
        List<CommandDescriptor.Node> out = new ArrayList<>();
        JsonArray then = json.has("then") ? json.getAsJsonArray("then") : new JsonArray();
        for (JsonElement element : then) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(source + ": every 'then' entry must be an object");
            }
            out.add(node(element.getAsJsonObject(), source, executors));
        }
        return out;
    }

    private static CommandDescriptor.Node node(JsonObject json, String source,
                                               Function<VineId, VineCommandExecutor> executors) {
        VinePermission permission = permission(json, source);
        VineCommandExecutor executor = executor(json, "executor", source, executors);
        if (json.has("literal")) {
            String name = requiredString(json, "literal", source);
            String redirect = json.has("redirect") ? requiredString(json, "redirect", source) : null;
            return new CommandDescriptor.Literal(name, permission, executor,
                redirect == null ? children(json, source, executors, false) : List.of(), List.of(), redirect);
        }
        if (json.has("argument")) {
            String name = requiredString(json, "argument", source);
            if (json.has("redirect")) {
                throw new IllegalArgumentException(source + ": redirect applies to literal nodes only ('"
                    + name + "')");
            }
            return new CommandDescriptor.Argument(name, type(json, source, name), permission, executor,
                children(json, source, executors, false), List.of(), suggestions(json, source, name));
        }
        throw new IllegalArgumentException(source + ": every node needs either 'literal' or 'argument'");
    }

    private static ArgumentTypeRef<?> type(JsonObject json, String source, String name) {
        String id = requiredString(json, "type", source);
        return switch (id) {
            case "string" -> VineArgumentTypes.STRING;
            case "int" -> VineArgumentTypes.INT;
            case "long" -> VineArgumentTypes.LONG;
            case "double" -> VineArgumentTypes.DOUBLE;
            case "bool" -> VineArgumentTypes.BOOL;
            case "greedy" -> VineArgumentTypes.GREEDY;
            // JSON enums carry their own constants and deliver the chosen
            // constant name as a string: no consumer class is reachable from
            // data, and the compiler's enum path is the same one Java uses.
            default -> id.startsWith("enum:")
                ? VineArgumentTypes.jsonEnumeration(id.substring("enum:".length()), enumConstants(json, source, name))
                : throwUnsupportedType(source, name, id);
        };
    }

    private static ArgumentTypeRef<?> throwUnsupportedType(String source, String name, String id) {
        throw new IllegalArgumentException(source + ": argument '" + name + "' has unknown type '" + id
            + "' — one of string/int/long/double/bool/greedy/enum:<NAME> (engine argument types are Java-only)");
    }

    private static List<String> enumConstants(JsonObject json, String source, String name) {
        if (!json.has("values")) {
            throw new IllegalArgumentException(source + ": enum argument '" + name + "' needs a 'values' array");
        }
        List<String> values = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("values")) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static SuggestionSource suggestions(JsonObject json, String source, String name) {
        if (!json.has("suggests")) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        for (JsonElement element : json.getAsJsonArray("suggests")) {
            candidates.add(element.getAsString());
        }
        return SuggestionSource.of(candidates);
    }

    private static VinePermission permission(JsonObject json, String source) {
        if (!json.has("permission")) {
            return null;
        }
        return VinePermission.level(json.get("permission").getAsInt());
    }

    private static VineCommandExecutor executor(JsonObject json, String field, String source,
                                                Function<VineId, VineCommandExecutor> executors) {
        if (!json.has(field)) {
            return null;
        }
        VineId executorId = VineId.parse(requiredString(json, field, source));
        VineCommandExecutor resolved = executors.apply(executorId);
        if (resolved == null) {
            throw new IllegalArgumentException(source + ": unknown executor '" + executorId
                + "' — consumers register executors before descriptors load (VineCommands.registerExecutor)");
        }
        return resolved;
    }

    private static String requiredString(JsonObject json, String field, String source) {
        if (!json.has(field) || !json.get(field).isJsonPrimitive()) {
            throw new IllegalArgumentException(source + ": missing required string field '" + field + "'");
        }
        return json.get(field).getAsString();
    }
}
