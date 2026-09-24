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
        // JSON command descriptors (sub-06 Stage B) load here, not at the
        // registry freeze: consumers have registered their named executors by
        // now, and the first native command-dispatcher build happens during
        // server construction — before REGISTRIES_FROZEN — so a later load would
        // miss the only attach pass until /reload.
        dev.vineengine.vine.internal.command.CommandJsonLoader.Result commands =
            dev.vineengine.vine.internal.command.CommandJsonLoader.load(commandsOf(), loader(), codeSources());
        if (commands.descriptors() > 0) {
            System.getLogger("boot").log(System.Logger.Level.INFO,
                "[VINE] command json: " + commands.descriptors() + " descriptor(s) loaded");
        }
    }

    private static dev.vineengine.vine.internal.command.CommandService commandsOf() {
        Object engine = dev.vineengine.vine.internal.EngineAccess.get();
        return ((dev.vineengine.vine.internal.core.VineEngineImpl) engine).commandsService();
    }

    private static ClassLoader loader() {
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        return context != null ? context : ConsumerInitializers.class.getClassLoader();
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
                // Loaders wrap the same checkout in different URL schemes; the
                // helper resolves all of them (file/jar/union) to a real path.
                java.nio.file.Path path = dev.vineengine.vine.internal.DevPaths
                    .fileOf(source.getLocation());
                if (path == null) {
                    continue;
                }
                out.add(path);
                // Dev classpaths split artifacts and resources: an edited data
                // file lives in the module's resource output, and the JSON reload
                // layer must see it (Loom's remapped copies hide that dir from
                // classloader resource lookup entirely).
                java.nio.file.Path resources = dev.vineengine.vine.internal.DevPaths
                    .resourceOutputSibling(path);
                if (resources != null) {
                    out.add(resources);
                }
            } catch (Exception e) {
                // unlocatable consumer: skip
            }
        }
        return out;
    }
}
