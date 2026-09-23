package dev.vineengine.vine.registry;

import com.mojang.serialization.Codec;

/**
 * Consumer-definable kind of registrable content — the engine's extension point
 * (sub-02 §2). Every content subsystem (blocks, quests, audio, …) defines one
 * {@code DescriptorType} per kind; consumers may define their own.
 *
 * <p>Implementations are identity tokens: hold exactly one instance (a static
 * singleton), pass that same instance to {@code defineType}, {@code register},
 * and {@code get}. Two instances claiming the same {@link #registryId()} fail
 * {@code defineType} explicitly — the engine never guesses which one owns the id.
 *
 * <p>The {@link Codec} in this signature is {@code com.mojang.serialization.Codec}
 * (DataFixerUpper) — the single external Mojang <em>library</em> type allowed in
 * vine-api signatures (§5.1); no {@code net.minecraft} or loader types ever appear.
 */
public interface DescriptorType<D> {

    /**
     * Unique id of this descriptor type itself, e.g. {@code vine:block} or
     * {@code mymod:ability}. Ids are global across all types.
     */
    VineId registryId();

    /**
     * The single source of truth for every representation of {@code D}: Java
     * authoring, datapack-style JSON, and network sync all derive from it (§5.2).
     */
    Codec<D> codec();

    /**
     * Structural (static startup registry, one JVM-session freeze) or design
     * (vanilla dynamic datapack registry: datapack override, {@code /reload},
     * optional sync). See {@link DescriptorClass}.
     */
    DescriptorClass descriptorClass();

    /**
     * {@link DescriptorClass#DESIGN} only: {@code true} = synced dynamic registry,
     * {@code false} = server-only. Meaningless for structural types;
     * {@code VineRegistries.defineType} rejects the combination explicitly.
     */
    boolean syncToClient();

    /**
     * Mirrors vanilla's {@code SKIP_WHEN_EMPTY} on the wire: an empty synced
     * registry sends nothing to clients.
     */
    default boolean skipWhenEmpty() {
        return true;
    }
}
