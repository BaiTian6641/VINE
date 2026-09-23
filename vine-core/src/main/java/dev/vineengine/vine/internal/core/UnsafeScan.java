package dev.vineengine.vine.internal.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Boot-time {@code @VineUnsafe} consistency scan (sub-01 Stage E, §5.1): walks
 * the consumer classpath, checks each jar for {@code VineUnsafe} in class-file
 * bytecode (constant-pool scan — never classloading) and for the
 * {@code Vine-Unsafe: true} manifest flag, and reports mismatches in either
 * direction. Directory entries (dev classpaths) are scanned for bytecode only —
 * they have no manifest to declare.
 *
 * <p>The class-file marker is the UTF8 constant {@code Ldev/vineengine/vine/VineUnsafe;}
 * — the annotation type descriptor, present wherever the annotation is applied
 * (CLASS retention). Scanning is best-effort by design: an unreadable entry is
 * reported, never fatal — the scan must not break a boot it merely audits.
 */
final class UnsafeScan {

    /** One jar whose declaration state mismatches its bytecode. */
    record Violation(Path jar, boolean annotated, boolean flag) {

        String describe() {
            if (annotated) {
                return jar.getFileName() + ": classes carry @VineUnsafe but the manifest lacks 'Vine-Unsafe: true'";
            }
            return jar.getFileName() + ": manifest declares 'Vine-Unsafe: true' but no class carries @VineUnsafe";
        }
    }

    /** Everything the scan examined, for logs and harnesses. */
    record Report(List<Path> scanned, List<Path> skipped, List<Violation> violations) {

        boolean clean() {
            return violations.isEmpty();
        }
    }

    private static final String DESCRIPTOR = "Ldev/vineengine/vine/VineUnsafe;";

    private UnsafeScan() {
    }

    /** Scans the process classpath (jars + directories). */
    static Report scanClasspath(String classPath) {
        List<Path> scanned = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        List<Violation> violations = new ArrayList<>();
        if (classPath == null || classPath.isEmpty()) {
            return new Report(scanned, skipped, violations);
        }
        for (String element : classPath.split(java.io.File.pathSeparator)) {
            if (element.isEmpty()) {
                continue;
            }
            Path path = Path.of(element);
            if (isEngineArtifact(path)) {
                // §5.1 audits consumer jars; VINE's own artifacts legitimately
                // carry the descriptor (this scanner's constant pool is one).
                continue;
            }
            try {
                if (Files.isDirectory(path)) {
                    // Dev classpath: bytecode visible, no manifest to declare with.
                    if (containsAnnotatedClasses(path)) {
                        violations.add(new Violation(path, true, false));
                    } else {
                        scanned.add(path);
                    }
                } else if (Files.isRegularFile(path)) {
                    scanJar(path, scanned, violations);
                }
            } catch (IOException e) {
                skipped.add(path);
            }
        }
        return new Report(scanned, skipped, violations);
    }

    /** True for VINE's own jars/dirs (any path segment starting {@code vine-}/{@code driver-}). */
    private static boolean isEngineArtifact(Path path) {
        for (Path segment : path) {
            String name = segment.toString();
            if (name.startsWith("vine-") || name.startsWith("driver-")) {
                return true;
            }
        }
        return false;
    }

    private static void scanJar(Path jar, List<Path> scanned, List<Violation> violations)
            throws IOException {
        boolean flag;
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            flag = manifest != null && "true".equalsIgnoreCase(
                String.valueOf(manifest.getMainAttributes().getValue(new Attributes.Name("Vine-Unsafe"))));
        } catch (IllegalArgumentException e) {
            // Not a jar (module path entry, native lib); skip silently.
            return;
        }
        boolean annotated = false;
        try (JarFile file = new JarFile(jar.toFile())) {
            var entries = file.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (!entry.getName().endsWith(".class") || entry.getName().endsWith("module-info.class")) {
                    continue;
                }
                try (InputStream in = file.getInputStream(entry)) {
                    if (containsDescriptor(in.readAllBytes())) {
                        annotated = true;
                        break;
                    }
                }
            }
        }
        if (annotated != flag) {
            violations.add(new Violation(jar, annotated, flag));
        } else {
            scanned.add(jar);
        }
    }

    private static boolean containsAnnotatedClasses(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.toString().endsWith(".class"))
                .anyMatch(p -> {
                    try {
                        return containsDescriptor(Files.readAllBytes(p));
                    } catch (IOException e) {
                        return false;
                    }
                });
        }
    }

    /** Constant-pool scan: the descriptor UTF8 entry implies an applied annotation. */
    private static boolean containsDescriptor(byte[] classBytes) {
        byte[] needle = DESCRIPTOR.getBytes(StandardCharsets.UTF_8);
        outer:
        for (int i = 0; i + needle.length <= classBytes.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (classBytes[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
