package dev.vineengine.vine.content;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.registry.DescriptorClass;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.VineId;

/**
 * The engine-owned structural content kinds: {@code vine:block} and
 * {@code vine:item} (sub-07 Stage A), and {@code vine:entity} (sub-08 Stage A). Consumers register {@link BlockDescriptor}s and
 * {@link ItemDescriptor}s under these tokens via
 * {@code VineRegistries.register} during {@code VineInitializer.init()} —
 * the engine defines both types itself at boot, before any consumer or driver
 * code runs, so a consumer never needs (and must never attempt) its own
 * {@code defineType} for them.
 *
 * <p>Identity tokens per the {@link DescriptorType} contract: exactly one
 * instance per kind exists here, and drivers compare by this instance when
 * they route a structural type to native block/item materialization instead
 * of a vine-owned registry (the sub-07 extension of the sub-02 seam).
 *
 * <p>Structural, server-visible: one JVM-session freeze like every static
 * content kind; client sync of content data is a later design-class concern.
 */
public final class VineContent {

    /** The block kind: structural type {@code vine:block}. */
    public static final DescriptorType<BlockDescriptor> BLOCK_TYPE =
        new StructuralType<>(VineId.of("vine", "block"), BlockDescriptor.CODEC);

    /** The item kind: structural type {@code vine:item}. */
    public static final DescriptorType<ItemDescriptor> ITEM_TYPE =
        new StructuralType<>(VineId.of("vine", "item"), ItemDescriptor.CODEC);

    /**
     * The entity kind: structural type {@code vine:entity} (sub-08 Stage A). Entity
     * types are structural by necessity, not by preference — a native entity kind
     * cannot be redefined under a running world, so this path is static startup
     * registration with a single JVM-session freeze like every other structural kind.
     */
    public static final DescriptorType<dev.vineengine.vine.entity.EntityDescriptor> ENTITY_TYPE =
        new StructuralType<>(VineId.of("vine", "entity"), dev.vineengine.vine.entity.EntityDescriptor.CODEC);

    private VineContent() {
    }

    /** Shared shape of the two engine-owned kinds; identity is instance identity. */
    private record StructuralType<D>(VineId registryId, Codec<D> codec) implements DescriptorType<D> {

        @Override
        public DescriptorClass descriptorClass() {
            return DescriptorClass.STRUCTURAL;
        }

        @Override
        public boolean syncToClient() {
            // Structural: static startup content, never a synced dynamic registry.
            return false;
        }
    }
}
