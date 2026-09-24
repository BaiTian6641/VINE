package dev.vineengine.vine.testmod.command;

import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.internal.DevPaths;
import dev.vineengine.vine.registry.VineId;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * JSON command exemplar (sub-06 Stage B) plus the reload fixture (Stage E).
 *
 * <p>Registers the behavior that {@code data/vine_test/vine/commands/quest.json}
 * references by id. A JSON node cannot carry a lambda, so executors are the seam —
 * and resolution happens at load time, which makes a typo a load failure instead
 * of a command that parses and does nothing.
 *
 * <p>The file proves the data path end to end: op-level permission, enum
 * argument with data-declared constants, literal suggestion list, a redirect
 * alias into another consumer's tree, and executor references — all parsed by
 * the engine's own compiler, so the merge policy and dispatcher walker treat it
 * exactly like a Java descriptor.
 *
 * <p>The reload half writes a *new* descriptor into this mod's dev resource
 * output, which the engine re-reads on every dispatcher build (each
 * {@code /reload}). The file name is unique, so it wins discovery even when the
 * shipped descriptor comes from a remapped jar — the engine's scan is per
 * resource path.
 */
public final class CommandJsonExemplar {

    private static final String RELOAD_FIXTURE_JSON = """
        {
          "id": "vine_test:reload_probe",
          "root": "vinereLoadProbe",
          "permission": 2,
          "then": [
            { "literal": "run", "executor": "vine_test:json_reload" }
          ]
        }
        """;

    private CommandJsonExemplar() {
    }

    /** Registers the executors the JSON descriptors reference. */
    public static void register() {
        VineCommands commands = VineCommands.get();
        commands.registerExecutor(VineId.of(MOD_ID, "json_start"), ctx -> {
            ctx.feedback("quest json: started");
            return 1;
        });
        commands.registerExecutor(VineId.of(MOD_ID, "json_mode"), ctx -> {
            ctx.feedback("quest json: mode=" + ctx.argument("mode", String.class));
            return 1;
        });
        commands.registerExecutor(VineId.of(MOD_ID, "json_status"), ctx -> {
            ctx.feedback("quest json: status ok");
            return 1;
        });
        commands.registerExecutor(VineId.of(MOD_ID, "json_reload"), ctx -> {
            ctx.feedback("reload probe: descriptor live");
            return 1;
        });
    }

    /**
     * Writes or removes the reload fixture in this mod's dev resource output.
     * When the mod runs from a jar with no writable resource sibling there is
     * nothing to edit, and the command says so instead of pretending.
     */
    static String writeReloadFixture(boolean present) {
        java.nio.file.Path resources = resourceOutput();
        if (resources == null) {
            return "reload fixture unavailable (resource: " + resourceUrl() + ")";
        }
        java.nio.file.Path target = resources.resolve("data/vine_test/vine/commands/reload_probe.json");
        try {
            if (present) {
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.writeString(target, RELOAD_FIXTURE_JSON);
                return "reload fixture written at " + target;
            }
            java.nio.file.Files.deleteIfExists(target);
            return "reload fixture removed";
        } catch (java.io.IOException e) {
            return "reload fixture failed: " + e;
        }
    }

    /**
     * The writable command data directory. Two derivations, because loaders
     * differ: the run directory pins the repo root deterministically (a dev
     * server's cwd is {@code <repo>/drivers/<cell>/run}), and the resource URL
     * covers layouts that keep resources on disk. {@link DevPaths} resolves the
     * loader URL schemes (file/jar/union) and the dev resource sibling, so the
     * loader-shape knowledge lives in one place.
     */
    private static java.nio.file.Path resourceOutput() {
        java.nio.file.Path fromCwd = devResourceOutputFromCwd();
        if (fromCwd != null) {
            return fromCwd;
        }
        java.net.URL quest = CommandJsonExemplar.class.getClassLoader()
            .getResource("data/vine_test/vine/commands/quest.json");
        return DevPaths.resourceOutputSibling(DevPaths.fileOf(quest));
    }

    private static java.nio.file.Path devResourceOutputFromCwd() {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
            for (int up = 0; up < 5 && dir != null; up++) {
                if ("drivers".equals(String.valueOf(dir.getFileName()))) {
                    java.nio.file.Path root = dir.getParent();
                    if (root == null) {
                        return null;
                    }
                    java.nio.file.Path resources = root.resolve("vine-testmod/build/resources/main");
                    return java.nio.file.Files.isDirectory(resources) ? resources : null;
                }
                dir = dir.getParent();
            }
            return null;
        } catch (RuntimeException unexpected) {
            return null;
        }
    }

    /** The descriptor's resource URL, for the unavailable-path diagnostic. */
    private static String resourceUrl() {
        java.net.URL quest = CommandJsonExemplar.class.getClassLoader()
            .getResource("data/vine_test/vine/commands/quest.json");
        return quest == null ? "not found" : quest.toString();
    }
}
