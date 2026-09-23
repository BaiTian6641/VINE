package dev.vineengine.vine.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Placeholder authoring hint for how a descriptor's assets should be cooked
 * (sub-07 Stage A). Until the sub-02 content pipeline and the sub-16/17 client
 * phases land, this is deliberately the <em>entire</em> model surface: drivers
 * log it at materialization and ship nothing — no client render wiring exists
 * in this stage by design.
 *
 * <p><b>Invariant:</b> a model hint never influences gameplay identity or
 * equality of a descriptor — it is cooked data, ignored by every engine
 * behavior path.
 */
public record ModelHint(Kind kind) {

    /** The placeholder cooking kinds drivers emit before sub-16/17 exist. */
    public enum Kind {

        /** Plain cube-all blockstate/model — the block placeholder. */
        CUBE_ALL,

        /** Plain 2D generated item model — the item placeholder. */
        GENERATED
    }

    private static final Codec<Kind> KIND = Codec.STRING.comapFlatMap(
        name -> {
            try {
                return DataResult.success(Kind.valueOf(name));
            } catch (IllegalArgumentException e) {
                return DataResult.error(() -> "unknown model kind: " + name);
            }
        },
        Kind::name);

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ModelHint> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        KIND.fieldOf("kind").forGetter(ModelHint::kind)
    ).apply(instance, ModelHint::new));

    /** Placeholder for a block descriptor. */
    public static ModelHint cubeAll() {
        return new ModelHint(Kind.CUBE_ALL);
    }

    /** Placeholder for an item descriptor. */
    public static ModelHint generated() {
        return new ModelHint(Kind.GENERATED);
    }

    public ModelHint {
        if (kind == null) {
            throw new IllegalArgumentException("ModelHint.kind must not be null");
        }
    }
}
