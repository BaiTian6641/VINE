package dev.vineengine.vine.testmod.registry;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;

/**
 * Java-path registration of the marker exemplar (sub-02 Stage B), plus the
 * post-freeze resolution probe that stage's acceptance greps for.
 *
 * <p>Registration runs directly from consumer init — the VineInitializer
 * contract guarantees init fires exactly once while REGISTRIES_OPEN is in
 * effect, so define/register calls here are always legal and never replay.
 *
 * <p>Dual-path invariant (sub-22 §4): the JSON twin
 * {@code data/vinetest/vinetest/marker/example.json} (structural JSON
 * authoring, sub-02 Stage F) decodes to this same entry under this same id;
 * the TCK registration scenario asserts both paths yield identical ids.
 */
public final class Testmarkers {

    /** The single marker entry; the JSON twin uses this exact id. */
    public static final VineId EXAMPLE_ID = VineId.of("vinetest", "example");

    /**
     * Optional markers (sub-02 Stage D acceptance): registered only while their
     * flag file holds {@code on} in the server working directory, so a TCK
     * scenario can make content appear and disappear across reboots and watch
     * the persistent id map's missing-content policy.
     */
    /** JSON-only entry (sub-02 Stage F): no Java registration, shipped as data/. */
    public static final VineId JSON_ONLY_ID = VineId.of("vinetest", "json_only");
    public static final VineId OPTIONAL_ID = VineId.of("vinetest", "optional");
    public static final VineId OPTIONAL2_ID = VineId.of("vinetest", "optional2");
    private static final java.nio.file.Path OPTIONAL_FLAG =
        java.nio.file.Path.of("vine_tck_optional.enabled");
    private static final java.nio.file.Path OPTIONAL2_FLAG =
        java.nio.file.Path.of("vine_tck_optional2.enabled");

    private Testmarkers() {
    }

    /** A file's trimmed content, or empty when absent/unreadable. */
    private static String flagContent(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.exists(path)
                ? java.nio.file.Files.readString(path).trim()
                : "";
        } catch (java.io.IOException e) {
            return "";
        }
    }

    /** A flag file whose trimmed content is {@code on} enables the marker. */
    private static boolean flagOn(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.exists(path)
                && "on".equals(java.nio.file.Files.readString(path).trim());
        } catch (java.io.IOException e) {
            return false;
        }
    }

    /**
     * Defines the marker type, registers the example entry, and arms the
     * post-freeze probe: after REGISTRIES_FROZEN the holder must resolve
     * through the same type token, proving the structural path survived the
     * freeze. The probe prints to stdout so headless boot logs carry it
     * regardless of the cell's logging setup.
     */
    public static void register(VineEngine engine) {
        // Missing-content policy (sub-02 Stage D): file-driven so a TCK scenario
        // can change it between reboots and watch the id map react at restore.
        String policy = flagContent(java.nio.file.Path.of("vine_tck_policy.txt"));
        if (!policy.isEmpty()) {
            VineRegistries.setMissingContentPolicy("vinetest",
                dev.vineengine.vine.registry.MissingContentPolicy.valueOf(
                    policy.toUpperCase(java.util.Locale.ROOT)));
        }
        VineRegistries.defineType(TestmarkerType.INSTANCE);
        VineRegistries.register(TestmarkerType.INSTANCE, EXAMPLE_ID, new Testmarker("example", 1));
        if (flagOn(OPTIONAL_FLAG)) {
            VineRegistries.register(TestmarkerType.INSTANCE, OPTIONAL_ID, new Testmarker("optional", 2));
        }
        if (flagOn(OPTIONAL2_FLAG)) {
            VineRegistries.register(TestmarkerType.INSTANCE, OPTIONAL2_ID, new Testmarker("optional2", 3));
        }
        engine.onPhase(EnginePhase.REGISTRIES_FROZEN, change -> {
            var holder = VineRegistries.get(TestmarkerType.INSTANCE, EXAMPLE_ID)
                .orElseThrow(() -> new IllegalStateException(
                    "testmod marker vanished at freeze — structural registration broken"));
            System.out.println("vine-testmod: marker holder resolved post-freeze id="
                + holder.id() + " runtimeId=" + holder.runtimeId());
            // Stage F: a JSON-only entry must resolve through the same path.
            var jsonOnly = VineRegistries.get(TestmarkerType.INSTANCE, JSON_ONLY_ID)
                .orElseThrow(() -> new IllegalStateException(
                    "JSON-only marker missing at freeze — structural JSON loading broken"));
            System.out.println("vine-testmod: structural json json_only present=true weight="
                + jsonOnly.value().weight());
        });
    }
}
