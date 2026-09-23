package dev.vineengine.vine.testmod.registry;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.registry.DescriptorClass;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.VineId;

/**
 * The testmod's consumer-defined structural descriptor type — proof that the
 * registry extension point works for content owned outside the engine
 * (sub-02 §2, Stage B).
 *
 * <p>Identity token per the {@link DescriptorType} contract: exactly one
 * instance exists ({@link #INSTANCE}), and that same instance is passed to
 * define/register/get.
 *
 * <p>The {@code vinetest} namespace (distinct from the testmod's own
 * {@code vine_test} content namespace) is pinned by sub-02 Stage B's
 * acceptance probe, which greps the post-freeze resolution line for it.
 */
public final class TestmarkerType implements DescriptorType<Testmarker> {

    /** The singleton identity token. */
    public static final TestmarkerType INSTANCE = new TestmarkerType();

    /** Type id; global across all descriptor types. */
    public static final VineId REGISTRY_ID = VineId.of("vinetest", "marker");

    private TestmarkerType() {
    }

    @Override
    public VineId registryId() {
        return REGISTRY_ID;
    }

    @Override
    public Codec<Testmarker> codec() {
        return Testmarker.CODEC;
    }

    @Override
    public DescriptorClass descriptorClass() {
        // Structural: static startup content, one JVM-session freeze.
        return DescriptorClass.STRUCTURAL;
    }

    @Override
    public boolean syncToClient() {
        // Meaningless for structural types; the marker is server-side data.
        return false;
    }
}
