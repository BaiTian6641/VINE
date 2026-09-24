package dev.vineengine.vine.internal;

import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-run path derivation shared by the engine and consumers (sub-06 Stage E).
 *
 * <p>Loaders wrap the same checkouts in different URL schemes — {@code file:} for
 * a plain class dir, {@code jar:file:} for a jar, and {@code union:…jar%23&lt;id&gt;!}
 * under ModLauncher — and a mod's resource output often sits *next to* the artifact
 * the classloader serves ({@code <module>/build/libs/x.jar} vs
 * {@code <module>/build/resources/main}). The JSON command reload layer and the
 * testmod's dev fixtures both need the same answers, so the parsing lives here
 * instead of being re-derived (and re-broken) per caller.
 */
public final class DevPaths {

    private DevPaths() {
    }

    /**
     * The filesystem path a code-source or resource URL points at — a directory,
     * a jar, or {@code null} for schemes that do not name a real path.
     */
    public static Path fileOf(URL location) {
        if (location == null) {
            return null;
        }
        return fileOf(location.toString());
    }

    /** {@link #fileOf(URL)} over the raw URL text. */
    public static Path fileOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            if (url.startsWith("file:")) {
                return Path.of(java.net.URI.create(url));
            }
            // jar:file:/C:/…/x.jar!/… and union:/C:/…/x.jar%23133!/…: both name the
            // artifact before the archive separator, with a leading slash on
            // Windows drive paths.
            int jarEnd = url.indexOf(".jar");
            if (jarEnd <= 0) {
                return null;
            }
            String spec = url.substring(url.indexOf(':') + 1, jarEnd + 4);
            if (spec.length() > 2 && spec.charAt(0) == '/'
                && Character.isLetter(spec.charAt(1)) && spec.charAt(2) == ':') {
                spec = spec.substring(1);
            }
            return Path.of(URLDecoder.decode(spec, StandardCharsets.UTF_8));
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    /**
     * The dev resource output ({@code <module>/build/resources/main}) belonging
     * to {@code classOrJar}, when it exists.
     *
     * <p>Recognises both dev layouts: an unremapped class dir
     * ({@code <module>/build/classes/java/main}) and a built jar
     * ({@code <module>/build/libs/<name>.jar}).
     */
    public static Path resourceOutputSibling(Path classOrJar) {
        if (classOrJar == null) {
            return null;
        }
        Path module = null;
        if (classOrJar.endsWith(Path.of("classes", "java", "main"))) {
            // <module>/build/classes/java/main -> <module>
            module = classOrJar;
            for (int up = 0; up < 4 && module != null; up++) {
                module = module.getParent();
            }
        } else if (Files.isRegularFile(classOrJar) && classOrJar.getFileName() != null
            && classOrJar.getFileName().toString().endsWith(".jar")) {
            Path libs = classOrJar.getParent();
            Path build = libs == null ? null : libs.getParent();
            if (libs != null && build != null
                && "libs".equals(String.valueOf(libs.getFileName()))
                && "build".equals(String.valueOf(build.getFileName()))) {
                module = build.getParent();
            }
        }
        if (module == null) {
            return null;
        }
        Path resources = module.resolve(Path.of("build", "resources", "main"));
        return Files.isDirectory(resources) ? resources : null;
    }

    /** {@code data/} inside a dev resource output or jar-derived module. */
    public static Path dataDir(Path classOrJar) {
        Path resources = resourceOutputSibling(classOrJar);
        return resources == null ? null : resources.resolve("data");
    }
}
