package dev.vineengine.vine.internal.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.vineengine.vine.command.CommandDescriptor;

/**
 * Discovers and loads JSON command descriptors (sub-06 Stage B) from
 * {@code data/<ns>/vine/commands/<name>.json} across the classloader's data
 * roots, the consumer code sources, and the JVM classpath — the same three
 * views the structural loader uses, because loaders hide mod jars from
 * resource lookup in different ways (Loom's Knot sees resources; FML secure
 * jars in dev runs do not).
 *
 * <p>Discovery is deliberately separate from {@code StructuralJsonLoader}: that
 * loader's candidate shape is registry-keyed ({@code data/<entry-ns>/<registry-ns>/<registry-path>/<entry>.json})
 * and its entries are frozen registrations, while commands are dispatcher
 * descriptors with a reloadable layer. Sharing the walk would couple two
 * independent policies for a few lines of file IO.
 *
 * <p>Loading is all-or-nothing per pass: one malformed file fails the pass with
 * the file named, and the engine keeps whatever layer it had.
 */
public final class CommandJsonLoader {

    private static final System.Logger LOG = System.getLogger(CommandBridge.LOG_NAME);

    /** What one load pass did, for logs and the TCK's parity check. */
    public record Result(int descriptors, int files) {
    }

    private CommandJsonLoader() {
    }

    /** Loads every discovered descriptor and applies them as the JSON layer. */
    public static Result load(CommandService service, ClassLoader loader, List<Path> consumerRoots) {
        Map<String, String> found = new LinkedHashMap<>();  // resource path -> json
        for (java.net.URL url : resources(loader, "data")) {
            LOG.log(System.Logger.Level.INFO, "[VINE] command json root: " + url);
            collectFromUrl(url, found);
        }
        for (Path root : consumerRoots) {
            try {
                LOG.log(System.Logger.Level.INFO, "[VINE] command json consumer root: " + root);
                if (Files.isDirectory(root)) {
                    collectFromDirectory(root.resolve("data"), found);
                } else if (Files.isRegularFile(root)) {
                    collectFromJar(root, found);
                }
            } catch (IOException e) {
                // unreadable consumer root: skip
            }
        }
        for (String element : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (element.isEmpty()) {
                continue;
            }
            Path root = Path.of(element);
            try {
                if (Files.isDirectory(root)) {
                    collectFromDirectory(root.resolve("data"), found);
                } else if (Files.isRegularFile(root)) {
                    collectFromJar(root, found);
                }
            } catch (IOException e) {
                // unreadable classpath entry: skip
            }
        }
        List<CommandDescriptor> descriptors = new ArrayList<>();
        List<String> paths = new java.util.ArrayList<>(found.keySet());
        java.util.Collections.sort(paths);
        for (String path : paths) {
            String json = found.get(path);
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            descriptors.add(CommandJsonCodec.parse(object, path, service::executor));
            LOG.log(System.Logger.Level.INFO, "[VINE] command json: " + path);
        }
        service.applyJson(descriptors);
        if (!descriptors.isEmpty()) {
            LOG.log(System.Logger.Level.INFO, "[VINE] command json layer: " + descriptors.size()
                + " descriptor(s) from " + found.size() + " file(s)");
        }
        return new Result(descriptors.size(), found.size());
    }

    /** {@code data/<ns>/vine/commands/<name>.json} — five segments, ours only. */
    private static boolean isCommandFile(String resourcePath) {
        String[] segments = resourcePath.split("/");
        return segments.length == 5 && "data".equals(segments[0]) && !segments[1].isEmpty()
            && "vine".equals(segments[2]) && "commands".equals(segments[3])
            && segments[4].endsWith(".json");
    }

    private static List<java.net.URL> resources(ClassLoader loader, String name) {
        List<java.net.URL> out = new ArrayList<>();
        if (loader == null) {
            return out;
        }
        try {
            var enumeration = loader.getResources(name);
            while (enumeration.hasMoreElements()) {
                out.add(enumeration.nextElement());
            }
        } catch (IOException e) {
            // no enumerable roots: an audit never blocks a boot
        }
        return out;
    }

    private static void collectFromUrl(java.net.URL url, Map<String, String> out) {
        try {
            if ("file".equals(url.getProtocol())) {
                collectFromDirectory(Path.of(url.toURI()), out);
                return;
            }
            if ("jar".equals(url.getProtocol())) {
                String spec = url.getFile();
                int bang = spec.indexOf("!/");
                if (bang > 0) {
                    collectFromJar(Path.of(new java.net.URL(spec.substring(0, bang)).toURI()), out);
                }
            }
        } catch (Exception e) {
            // unreadable root: skip
        }
    }

    private static void collectFromDirectory(Path root, Map<String, String> out) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Path parent = root.getParent();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = (parent == null ? root.relativize(file) : parent.relativize(file))
                    .toString().replace('\\', '/');
                if (isCommandFile(relative)) {
                    out.putIfAbsent(relative, Files.readString(file, StandardCharsets.UTF_8));
                }
            }
        }
    }

    /**
     * A dev-run jar sits at {@code <module>/build/libs/<name>.jar} with its
     * resource output at {@code <module>/build/resources/main} — where an edited
     * descriptor lands before any rebuild (Loom's remapped copy hides that dir
     * from every classloader view, so without this a reload would replay the jar
     * contents).
     */
    private static void collectFromJar(Path jar, Map<String, String> out) throws IOException {
        if (jar.getFileName() != null && jar.getFileName().toString().endsWith(".jar")
            && jar.getParent() != null && jar.getParent().getFileName() != null
            && "libs".equals(jar.getParent().getFileName().toString())
            && jar.getParent().getParent() != null && jar.getParent().getParent().getFileName() != null
            && "build".equals(jar.getParent().getParent().getFileName().toString())
            && jar.getParent().getParent().getParent() != null) {
            Path resources = jar.getParent().getParent().getParent().resolve("build/resources/main/data");
            if (Files.isDirectory(resources)) {
                collectFromDirectory(resources, out);
            }
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("data/")
                    || !entry.getName().endsWith(".json") || !isCommandFile(entry.getName())) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    out.putIfAbsent(entry.getName(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        } catch (IllegalArgumentException notAJar) {
            // module path entry or native library; nothing to scan
        }
    }

}
