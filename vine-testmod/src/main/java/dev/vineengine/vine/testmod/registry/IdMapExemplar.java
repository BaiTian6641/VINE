package dev.vineengine.vine.testmod.registry;

import dev.vineengine.vine.registry.VineRegistries;

/**
 * Persistent-id exemplar (sub-02 Stage D): prints the runtime ids structural
 * markers currently hold, so a TCK scenario can watch the engine's per-world
 * {@code VineId}<->int map assign, retain (KEEP) and restore ids across reboots
 * while markers appear and disappear.
 */
public final class IdMapExemplar {

    private IdMapExemplar() {
    }

    /** Command entry: one line per marker, present or absent. */
    public static void report() {
        System.out.println("vine-testmod: idmap example="
            + runtimeId(Testmarkers.EXAMPLE_ID) + " optional="
            + runtimeId(Testmarkers.OPTIONAL_ID) + " optional2="
            + runtimeId(Testmarkers.OPTIONAL2_ID));
        System.out.println("vine-testmod: idmap policy="
            + VineRegistries.missingContentPolicyFor("vinetest"));
        var map = VineRegistries.idMap();
        StringBuilder rendered = new StringBuilder();
        for (var entry : map.entrySet()) {
            rendered.append(rendered.isEmpty() ? "" : ",").append(entry.getKey())
                .append('=').append(entry.getValue());
        }
        System.out.println("vine-testmod: idmap map=" + rendered);
    }

    private static String runtimeId(dev.vineengine.vine.registry.VineId id) {
        return VineRegistries.get(TestmarkerType.INSTANCE, id)
            .map(holder -> Integer.toString(holder.runtimeId()))
            .orElse("absent");
    }
}
