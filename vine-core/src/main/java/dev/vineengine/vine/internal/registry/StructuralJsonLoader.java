package dev.vineengine.vine.internal.registry;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import dev.vineengine.vine.registry.DescriptorClass;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.registry.VineId;

/**
 * Startup JSON authoring for structural descriptors (sub-02 Stage F): before the
 * store freezes, the engine reads every classpath resource shaped
 * {@code data/<entry-ns>/<registry-ns>/<registry-path>/<entry>.json} whose
 * registry is a defined structural type, decodes it with that type's
 * {@code Codec} and registers it — so JSON and Java authoring are one path, and
 * structural JSON is read exactly once (never hot-reloadable).
 *
 * <p>A JSON entry whose id is also Java-registered must decode to the
 * <em>identical</em> value: identical twins are verified and skipped (no
 * duplicate registration), divergent ones fail the boot loudly. Discovery is
 * sorted, so registration order — and therefore the persistent id map — is
 * deterministic on every cell.
 *
 * <p>Consumers ship these files in their mod jar's {@code data/} folder; the
 * classpath is scanned as directories and jars, never classloaded.
 */
public final class StructuralJsonLoader {

    private static final System.Logger LOG = System.getLogger("vine.registry");

    /** What one load pass did, for logs and the TCK's cross-loader parity check. */
    public record Result(int registered, int identicalTwins) {
    }

    private StructuralJsonLoader() {
    }

    /**
     * Registers every structural JSON descriptor found on {@code classpath}
     * sources. Must run after consumer initializers (types defined) and before
     * {@link DescriptorStore#freeze()}.
     */
    public static Result load(DescriptorStore store, ClassLoader loader,
            java.util.List<Path> consumerRoots) {
        if (store.structuralRegistryIds().isEmpty()) {
            return new Result(0, 0);
        }
        LOG.log(System.Logger.Level.INFO, "[VINE] structural JSON: scanning "
            + store.structuralRegistryIds().size() + " structural registry id(s)");
        // Discovery rides the classloader, not java.class.path: mod loaders (Loom's
        // Knot, ModDevGradle) launch through their own classloader and leave the
        // JVM classpath property nearly empty, while resource lookup still sees
        // every consumer jar's data/ folder.
        Map<String, Candidate> found = new java.util.LinkedHashMap<>();
        List<java.net.URL> roots = resources(loader, "data");
        for (java.net.URL url : roots) {
            LOG.log(System.Logger.Level.INFO, "[VINE] structural JSON root: " + url);
            collectFromUrl(url, found);
        }
        // Consumer code sources: the only reliable view under loaders that hide
        // mod jars from resource lookup (FML secure jars in ModDevGradle dev runs).
        for (Path root : consumerRoots) {
            try {
                if (Files.isDirectory(root)) {
                    collectFromDirectory(root.resolve("data"), found);
                } else if (Files.isRegularFile(root)) {
                    collectFromJar(root, found);
                }
            } catch (IOException e) {
                // unreadable consumer root: skip
            }
        }
        // Belt and braces: some launchers keep the JVM classpath property populated
        // even when resource lookup is filtered.
        String classPath = System.getProperty("java.class.path", "");
        for (String element : classPath.split(java.io.File.pathSeparator)) {
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
                // unreadable entry: skip
            }
        }
        LOG.log(System.Logger.Level.INFO, "[VINE] structural JSON: " + roots.size()
            + " classloader data root(s), " + found.size() + " candidate file(s)");
        List<Candidate> candidates = new ArrayList<>(found.values());
        candidates.sort(Comparator.comparing(Candidate::registryId).thenComparing(Candidate::entryId));

        int registered = 0;
        int twins = 0;
        for (Candidate candidate : candidates) {
            DescriptorType<?> type = store.structuralType(candidate.registryId());
            if (type == null) {
                continue; // not a defined structural registry: not ours to load
            }
            if (loadOne(store, type, candidate)) {
                registered++;
            } else {
                twins++;
            }
        }
        return new Result(registered, twins);
    }

    /** @return true when a new entry was registered, false when a twin was verified */
    private static <D> boolean loadOne(DescriptorStore store, DescriptorType<D> type, Candidate candidate) {
        D decoded = decode(type, candidate);
        Optional<Holder<D>> existing = store.get(type, candidate.entryId());
        if (existing.isPresent()) {
            if (existing.get().value().equals(decoded)) {
                return false; // verified Java twin: one entry, two representations
            }
            throw new IllegalStateException("structural JSON " + candidate.source()
                + " for " + candidate.entryId() + " differs from the Java-registered descriptor"
                + " — the two authoring paths must agree");
        }
        store.register(type, candidate.entryId(), decoded);
        return true;
    }

    private static <D> D decode(DescriptorType<D> type, Candidate candidate) {
        JsonElement json = JsonParser.parseString(candidate.json());
        DataResult<D> result = type.codec().parse(JsonOps.INSTANCE, json);
        if (result.result().isEmpty()) {
            throw new IllegalStateException("structural JSON " + candidate.source()
                + " rejected by the " + type.registryId() + " codec: "
                + result.error().map(DataResult.Error::message).orElse("unknown error"));
        }
        return result.result().get();
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    private record Candidate(VineId registryId, VineId entryId, String json, String source) {
    }

    /** {@code data/<entry-ns>/<registry-ns>/<registry-path>/<entry>.json} — five segments. */
    private static Candidate candidate(String resourcePath, String json, String source) {
        String[] segments = resourcePath.split("/");
        if (segments.length != 5 || !"data".equals(segments[0]) || !segments[4].endsWith(".json")) {
            return null;
        }
        try {
            VineId entryId = VineId.of(segments[1], segments[4].substring(0, segments[4].length() - 5));
            VineId registryId = VineId.of(segments[2], segments[3]);
            return new Candidate(registryId, entryId, json, source);
        } catch (RuntimeException invalid) {
            return null; // not an engine-shaped resource (vanilla data lives elsewhere)
        }
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

    /** One {@code data} root: a directory, or a jar entry root ({@code jar:file:...!/data}). */
    private static void collectFromUrl(java.net.URL url, Map<String, Candidate> out) {
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

    private static void collectFromDirectory(Path root, Map<String, Candidate> out) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Path parent = root.getParent();
        try (var walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                String relative = (parent == null ? root.relativize(file) : parent.relativize(file))
                    .toString().replace('\\', '/');
                Candidate candidate = candidate(relative,
                    Files.readString(file, StandardCharsets.UTF_8), file.toString());
                if (candidate != null) {
                    out.putIfAbsent(relative, candidate);
                }
            }
        }
    }

    private static void collectFromJar(Path jar, Map<String, Candidate> out) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("data/")
                        || !entry.getName().endsWith(".json")) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    Candidate candidate = candidate(entry.getName(),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8),
                        jar.getFileName() + "!" + entry.getName());
                    if (candidate != null) {
                        out.putIfAbsent(entry.getName(), candidate);
                    }
                }
            }
        } catch (IllegalArgumentException notAJar) {
            // module path entry or native library; nothing to scan
        }
    }
}
