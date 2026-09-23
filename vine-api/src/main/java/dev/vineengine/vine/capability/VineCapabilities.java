package dev.vineengine.vine.capability;

import java.util.Optional;

import dev.vineengine.vine.internal.CapabilityBackend;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.registry.VineId;

/**
 * Static entry point for the capability machinery (sub-04 §2): register
 * types, bind providers per scope, and query instances per target.
 *
 * <p>Types register during boot (open until {@code REGISTRIES_FROZEN});
 * queries stay open forever. Backed by vine-core's capability store through
 * the same {@code EngineAccess} seam as its siblings — calling any method
 * boots the engine, so driver bootstrap code uses {@code DriverContext}
 * instead.
 */
public final class VineCapabilities {

    private VineCapabilities() {
    }

    /**
     * Registers a capability type with the engine store.
     *
     * @throws IllegalStateException on a duplicate id or after
     *         {@code REGISTRIES_FROZEN}
     */
    public static <T> CapabilityType<T> register(CapabilityType<T> type) {
        return backend().register(type);
    }

    /** Binds {@code provider} to serve {@code type} on {@code scope}. */
    public static <T> void attach(CapabilityType<T> type, CapabilityScope scope,
            CapabilityProvider<T> provider) {
        backend().attach(type, scope, provider);
    }

    /**
     * The instance for (target, type), or empty when no provider serves that
     * pair — absence is a value, never an exception.
     */
    public static <T> Optional<T> find(CapabilityType<T> type, CapabilityTarget target) {
        return backend().find(type, target);
    }

    /**
     * Native interop query, probe-degraded (sub-04 §2 layering rule): the
     * driver wraps a foreign capability in the engine type, or reports empty
     * when the partner/loader path cannot serve it. Never throws.
     */
    public static <T> Optional<T> findForeign(VineId nativeId, Class<T> apiClass,
            CapabilityTarget target) {
        return backend().findForeign(nativeId, apiClass, target);
    }

    /** Drops every cached instance/state for {@code target} (uniform
     *  invalidation event; NF auto-invalidates, Fabric caches re-query). */
    public static void invalidate(CapabilityTarget target) {
        backend().invalidate(target);
    }

    /** Applies {@code type}'s clone policy from {@code oldTarget} to
     *  {@code newTarget} — called by drivers on copy-on-death events. */
    public static <T> void applyClone(CapabilityType<T> type, CapabilityTarget oldTarget,
            CapabilityTarget newTarget) {
        backend().applyClone(type, oldTarget, newTarget);
    }

    /**
     * Flushes every live capability tree for {@code target} through the storage
     * layer (sub-04 Stage C/D): capability state rides the target's attach point,
     * so it survives the target's own save/load and copies made from it.
     */
    public static void flush(CapabilityTarget target) {
        backend().flush(target);
    }

    private static CapabilityBackend backend() {
        if (EngineAccess.get() instanceof CapabilityBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "engine core does not implement the capability backend — vine-core version mismatch");
    }
}
