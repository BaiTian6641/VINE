package dev.vineengine.vine.ui;

import dev.vineengine.vine.Feature;

/**
 * The client capabilities a cell may or may not have (sub-16 §2). Declared here because the
 * engine relies on them: a consumer asks, and a cell that cannot render answers
 * {@code false} and degrades instead of crashing — the same Minimal Footprint rule the rest
 * of the engine follows, applied to drawing.
 *
 * <p>Ids are lowercase camelCase, as the engine's feature convention requires; they are the
 * strings a consumer matches against, never an enum name.
 */
public enum ClientFeature implements Feature {

    /** Screen descriptors can be materialized as native screens. */
    SCREENS("hasScreens"),

    /** HUD layers can be drawn. */
    HUD("hasHud"),

    /** Consumer keybinds can be registered. */
    KEYBINDS("hasKeybinds"),

    /** The cell can apply a cutscene frame (sub-23 Stage C): camera, actors, titles. */
    CUTSCENE_PRESENTATION("hasCutscenePresentation");

    private final String id;

    ClientFeature(String id) {
        this.id = id;
    }

    @Override
    public String id() {
        return id;
    }
}
