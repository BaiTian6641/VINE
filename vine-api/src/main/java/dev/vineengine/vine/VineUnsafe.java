package dev.vineengine.vine;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks engine internals a consumer deliberately reaches into (sub-01 §5.1):
 * anything the Prime Invariant would normally hide. Released mods may ship
 * {@code @VineUnsafe} code only when their jar declares {@code Vine-Unsafe: true}
 * in {@code MANIFEST.MF}; vine-core scans consumer jars at boot and warns on
 * mismatches in either direction.
 *
 * <p>{@link RetentionPolicy#CLASS} on purpose: the marker survives into bytecode
 * so tooling (and the engine's own scan) sees it without classloading.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface VineUnsafe {

    /** Why the unsafe reach is necessary — appears in tooling output. */
    String reason() default "";
}
