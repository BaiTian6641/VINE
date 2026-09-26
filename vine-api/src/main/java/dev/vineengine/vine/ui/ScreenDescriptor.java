package dev.vineengine.vine.ui;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * A screen as data (sub-16 §2): a widget tree the engine lays out and a cell materializes.
 *
 * <p><b>Server or client?</b> A screen descriptor is content, not state: it describes what a
 * menu looks like, and a cell turns it into its own native screen. The <em>data behind</em> a
 * screen is a separate, server-owned question (the menu-sync pattern) — a screen never reads
 * the world directly, and the engine never renders.
 *
 * @param id        the screen's engine id
 * @param root      the widget tree
 * @param pausesGame whether the cell should pause single-player while it is open
 */
public record ScreenDescriptor(VineId id, Widget root, boolean pausesGame) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ScreenDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(ScreenDescriptor::id),
        Widget.CODEC.fieldOf("root").forGetter(ScreenDescriptor::root),
        Codec.BOOL.optionalFieldOf("pausesGame", false).forGetter(ScreenDescriptor::pausesGame)
    ).apply(instance, ScreenDescriptor::new));

    public ScreenDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(root, "root");
    }
}
