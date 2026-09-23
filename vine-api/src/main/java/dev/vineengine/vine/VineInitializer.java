package dev.vineengine.vine;

/**
 * Consumer bootstrap entrypoint — the loader-neutral init hook for pure-API
 * consumer jars (plan §6: per-version jars carry no per-version source, so no
 * loader entrypoint class exists to call consumer init).
 *
 * <p>Discovered via {@link java.util.ServiceLoader}: a consumer jar lists its
 * implementation in {@code META-INF/services/dev.vineengine.vine.VineInitializer}.
 * vine-core invokes {@link #init()} exactly once per process, right after engine
 * boot completes, while {@link EnginePhase#REGISTRIES_OPEN} is in effect — the
 * correct moment for {@code VineRegistries.defineType}/{@code register} and
 * {@code onPhase} subscriptions.
 *
 * <p><b>Invariant:</b> an initializer that throws fails the boot explicitly —
 * half-registered content never proceeds silently. With no consumers on the
 * classpath this mechanism is inert (Minimal Footprint, §5.1).
 */
public interface VineInitializer {

    /** Consumer init: declare descriptor types, register descriptors, subscribe events. */
    void init();
}
