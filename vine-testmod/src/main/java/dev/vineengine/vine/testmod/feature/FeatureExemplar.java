package dev.vineengine.vine.testmod.feature;

import dev.vineengine.vine.Feature;
import dev.vineengine.vine.VineEngine;

/**
 * Live-cell feature-matrix assertion (sub-01 Stage D acceptance): on 1.21.1,
 * {@code supports(unobfuscatedRuntime)} is false and
 * {@code supports(hasDataComponents)} is true. One printed line per query so
 * the {@code vine_test:features} TCK scenario can assert the ordered trace.
 */
public final class FeatureExemplar {

    private FeatureExemplar() {
    }

    /** Command entry: prints the acceptance pair for the running cell, one line per check. */
    public static void runProof() {
        VineEngine engine = VineEngine.get();
        System.out.println("vine-testmod: features supports(unobfuscatedRuntime)="
            + engine.supports(Feature.of("unobfuscatedRuntime")));
        System.out.println("vine-testmod: features supports(hasDataComponents)="
            + engine.supports(Feature.of("hasDataComponents")));
    }
}
