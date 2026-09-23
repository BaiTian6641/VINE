package dev.vineengine.vine.data;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a raw fast-path hook — a method that bypasses tree safety for proven
 * tick-hot reads/writes (sub-03 §2). Strictly benchmark-gated: the annotation
 * must carry the measurement that justifies it (TCK {@code voxeldata.perf}
 * numbers), and the perf budget re-checks it — an unjustified {@code @FastPath}
 * is a review failure, not a style choice.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface FastPath {

    /** Why this hook exists: the benchmark it won and the numbers it produced. */
    String justification();
}
