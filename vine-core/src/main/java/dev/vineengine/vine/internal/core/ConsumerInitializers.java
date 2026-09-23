package dev.vineengine.vine.internal.core;

import java.util.ServiceLoader;

import dev.vineengine.vine.VineInitializer;

/**
 * Runs consumer {@link VineInitializer}s once per process (sub-02 Stage B).
 *
 * <p>Invoked by the driver entrypoint right after engine boot returns — never
 * from inside {@code EngineAccess.boot}, where the facade is deliberately blocked
 * (initializers call {@code VineRegistries}, which needs the booted engine).
 * Registration is then genuinely open: the phase machine sits in
 * {@code REGISTRIES_OPEN} on every cell.
 *
 * <p>An initializer failure propagates: half-registered content is a boot
 * failure, never a silent skip. Zero consumers ⇒ zero work (Minimal Footprint).
 */
public final class ConsumerInitializers {

    private static boolean ran;
    private static final java.util.List<VineInitializer> LOADED = new java.util.ArrayList<>();

    private ConsumerInitializers() {
    }

    /** Idempotent: only the first call loads and runs initializers. */
    public static synchronized void run() {
        if (ran) {
            return;
        }
        ran = true;
        for (VineInitializer initializer : ServiceLoader.load(VineInitializer.class)) {
            LOADED.add(initializer);
            initializer.init();
        }
    }

    /**
     * Filesystem locations of the consumer jars/class dirs that provide
     * initializers, plus their dev-mode resources siblings — the roots the
     * structural JSON scan can read even when a mod loader (FML's secure jars)
     * hides mod resources from {@code ClassLoader#getResources}.
     */
    public static synchronized java.util.List<java.nio.file.Path> codeSources() {
        java.util.List<java.nio.file.Path> out = new java.util.ArrayList<>();
        for (VineInitializer initializer : LOADED) {
            var source = initializer.getClass().getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                continue;
            }
            try {
                java.nio.file.Path path = java.nio.file.Path.of(source.getLocation().toURI());
                out.add(path);
                // Dev classpaths split classes and resources into siblings.
                if (path.endsWith(java.nio.file.Path.of("classes", "java", "main"))) {
                    java.nio.file.Path module = path.getParent().getParent().getParent().getParent();
                    java.nio.file.Path resources =
                        module.resolve(java.nio.file.Path.of("build", "resources", "main"));
                    if (java.nio.file.Files.isDirectory(resources)) {
                        out.add(resources);
                    }
                }
            } catch (Exception e) {
                // unlocatable consumer: skip
            }
        }
        return out;
    }
}
