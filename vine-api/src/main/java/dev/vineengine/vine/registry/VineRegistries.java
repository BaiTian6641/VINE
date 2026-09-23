package dev.vineengine.vine.registry;

import java.util.Optional;

import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.internal.RegistryBackend;

/**
 * Static entry point for the descriptor machinery: define types, register
 * descriptors from Java, resolve holders (sub-02 §2). JSON authoring rides the
 * same path — a JSON descriptor is decoded with its type's {@code Codec} and
 * registered identically.
 *
 * <p>Backed by vine-core's {@code DescriptorStore}: writes (define/register) are
 * open during boot and throw after
 * {@link dev.vineengine.vine.EnginePhase#REGISTRIES_FROZEN}; reads stay open.
 * Duplicate type ids and duplicate descriptor ids fail explicitly — the engine
 * never guesses.
 *
 * <p>Calling any method boots the engine (same {@code ServiceLoader} seam as
 * {@code VineEngine.get()}), so a call from inside driver bootstrap is rejected
 * — drivers use {@code DriverContext} until boot completes.
 */
public final class VineRegistries {

    private VineRegistries() {
    }

    /**
     * Defines a descriptor type. Engine-boot phase only: call before any
     * {@link #register} for the type. Re-defining the same instance is a no-op;
     * a different instance claiming an already-defined {@code registryId} throws.
     *
     * @throws IllegalArgumentException if a structural type requests client sync
     * @throws IllegalStateException if the id is already defined by another
     *         instance, or registries are frozen
     */
    public static <D> void defineType(DescriptorType<D> type) {
        backend().defineType(type);
    }

    /**
     * Registers one descriptor under {@code id}; the type must already be
     * defined with this exact instance.
     *
     * @return the holder, carrying the descriptor's runtime-id slot
     * @throws IllegalStateException if the type is undefined, the id is already
     *         registered, or registries are frozen
     */
    public static <D> Holder<D> register(DescriptorType<D> type, VineId id, D data) {
        return backend().register(type, id, data);
    }

    /**
     * Resolves a registered descriptor. Empty when no entry has {@code id} —
     * including when the type was never defined, so a foreign consumer's absent
     * content reads as "not present", never as an error.
     *
     * @throws IllegalStateException if the id's type was defined by a different
     *         {@code DescriptorType} instance (returning its values through this
     *         token would be an unsound cast)
     */
    public static <D> Optional<Holder<D>> get(DescriptorType<D> type, VineId id) {
        return backend().get(type, id);
    }

    /**
     * Sets {@code namespace}'s missing-content policy (sub-02 Stage D), applied
     * the next time the persistent id map is restored. Default is
     * {@link MissingContentPolicy#KEEP}.
     */
    public static void setMissingContentPolicy(String namespace, MissingContentPolicy policy) {
        backend().setMissingContentPolicy(namespace, policy);
    }

    /** {@code namespace}'s current policy (never null; defaults to {@code KEEP}). */
    public static MissingContentPolicy missingContentPolicyFor(String namespace) {
        return backend().missingContentPolicyFor(namespace);
    }

    /**
     * Whether {@code key} (the id-map key {@code registryId entryId}) refers to
     * content that is registered right now — structural entries and loaded design
     * entries alike. Engine-internal consumers and the id map use it to decide
     * missing-content handling.
     */
    public static boolean isRegistered(String key) {
        return backend().isRegistered(key);
    }

    /**
     * Snapshot of the persistent {@code VineId}<->int map ({@code "registryId entryId"}
     * keys, sorted) for the current world — the tooling/TCK observability surface
     * behind {@code Holder#runtimeId}.
     */
    public static java.util.Map<String, Integer> idMap() {
        return backend().idMap();
    }

    private static RegistryBackend backend() {
        if (EngineAccess.get() instanceof RegistryBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "vine-core engine does not provide registry services — mismatched vine-api/vine-core jars");
    }
}
