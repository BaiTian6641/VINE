package dev.vineengine.vine.internal.driver1211.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The 1.21.1 cell's feature-probe matrix (§5.12). Feature ids — never version
 * strings — back {@code VineEngine.supports(...)}: {@link #supportedFeatures()} is
 * the id set reported in {@code CellInfo}, and {@link #probeMatrix()} is the full
 * true/false picture the driver logs at bind time.
 *
 * <p>Values are compile-time cell constants for M0: on 1.21.1 Data Components,
 * native Brigadier, and data-driven registries always exist, and the runtime is
 * always obfuscated (Yarn/intermediary or Mojmap-over-obf) with no Vulkan renderer.
 * sub-01 Stage D generalizes {@code supports()}; cells whose values can vary must
 * replace constants with runtime class/method-presence probes there.
 */
public final class CellProbes {

    public static final String HAS_DATA_COMPONENTS = "hasDataComponents";
    public static final String BRIGADIER_NATIVE = "brigadierNative";
    public static final String DATA_DRIVEN_REGISTRIES = "dataDrivenRegistries";
    public static final String UNOBFUSCATED_RUNTIME = "unobfuscatedRuntime";
    public static final String VULKAN_RENDERER = "vulkanRenderer";

    private CellProbes() {
    }

    /** Full probe picture, in stable log order. */
    public static Map<String, Boolean> probeMatrix() {
        Map<String, Boolean> matrix = new LinkedHashMap<>();
        matrix.put(HAS_DATA_COMPONENTS, true);
        matrix.put(BRIGADIER_NATIVE, true);
        matrix.put(DATA_DRIVEN_REGISTRIES, true);
        matrix.put(UNOBFUSCATED_RUNTIME, false);
        matrix.put(VULKAN_RENDERER, false);
        return java.util.Collections.unmodifiableMap(matrix);
    }

    /** Ids of the probes that hold on this cell — the {@code CellInfo} feature set. */
    public static Set<String> supportedFeatures() {
        return Set.of(HAS_DATA_COMPONENTS, BRIGADIER_NATIVE, DATA_DRIVEN_REGISTRIES);
    }
}
