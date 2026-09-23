package dev.vineengine.vine.registry;

/**
 * Which of the two registry families a descriptor type materializes into
 * (locked, plan §5.2). The class — not the loader — decides a type's
 * reload/sync semantics, so consumer code is identical on every cell.
 */
public enum DescriptorClass {

    /**
     * Static startup registries (blocks, items, entities, sounds, recipe types).
     * Materialized once per JVM session and frozen at
     * {@link dev.vineengine.vine.EnginePhase#REGISTRIES_FROZEN}; JSON is read once
     * before the freeze and is never hot-reloadable.
     */
    STRUCTURAL,

    /**
     * Vanilla dynamic datapack registries (abilities, quests, monster
     * definitions, skill nodes, loot modifiers). Datapack override, {@code /reload},
     * and server&rarr;client sync come from the vanilla machinery.
     *
     * <p><b>Invariant:</b> design holders are volatile — they are rebound on world
     * load and {@code /reload}; caching one across either is a defect.
     */
    DESIGN
}
