package dev.vineengine.vine.internal.core;

import java.util.Objects;
import java.util.Set;

import dev.vineengine.vine.Feature;

/**
 * The driver-reported feature-id set behind {@code VineEngine.supports(...)}
 * (sub-01 Stage D): immutable, id-based, filled once from the bound driver's
 * {@code CellInfo} (§5.12 — runtime probes, never version strings). The inert
 * engine (no driver) supports nothing.
 */
final class FeatureMatrix {

    static final FeatureMatrix EMPTY = new FeatureMatrix(Set.of());

    private final Set<String> ids;

    FeatureMatrix(Set<String> ids) {
        this.ids = Set.copyOf(Objects.requireNonNull(ids, "ids"));
    }

    boolean supports(Feature feature) {
        return ids.contains(feature.id());
    }

    Set<String> ids() {
        return ids;
    }
}
