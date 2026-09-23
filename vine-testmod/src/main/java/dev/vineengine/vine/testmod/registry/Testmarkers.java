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

    private Testmarkers() {
    }

    /**
     * Defines the marker type, registers the example entry, and arms the
     * post-freeze probe: after REGISTRIES_FROZEN the holder must resolve
     * through the same type token, proving the structural path survived the
     * freeze. The probe prints to stdout so headless boot logs carry it
     * regardless of the cell's logging setup.
     */
    public static void register(VineEngine engine) {
        VineRegistries.defineType(TestmarkerType.INSTANCE);
        VineRegistries.register(TestmarkerType.INSTANCE, EXAMPLE_ID, new Testmarker("example", 1));
        engine.onPhase(EnginePhase.REGISTRIES_FROZEN, change -> {
            var holder = VineRegistries.get(TestmarkerType.INSTANCE, EXAMPLE_ID)
                .orElseThrow(() -> new IllegalStateException(
                    "testmod marker vanished at freeze — structural registration broken"));
            System.out.println("vine-testmod: marker holder resolved post-freeze id="
                + holder.id() + " runtimeId=" + holder.runtimeId());
        });
    }
}
