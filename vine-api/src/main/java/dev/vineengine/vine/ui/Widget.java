package dev.vineengine.vine.ui;

import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * A widget (sub-16 §2). Sealed: the engine resolves layout and hit rectangles for a closed
 * vocabulary, and a cell materializes each kind through its own renderer. A "custom widget"
 * with no engine-side meaning would be a cell-specific entity the engine could not lay out,
 * which is exactly what this design refuses.
 */
public sealed interface Widget permits Widget.Button, Widget.Label, Widget.Slot, Widget.Group {

    /** Where this widget sits. */
    LayoutSpec layout();

    /** A button: a label a player can press. */
    record Button(VineId id, String label, LayoutSpec layout) implements Widget {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Button> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            VineId.CODEC.fieldOf("id").forGetter(Button::id),
            Codec.STRING.fieldOf("label").forGetter(Button::label),
            LayoutSpec.CODEC.fieldOf("layout").forGetter(Button::layout)
        ).apply(instance, Button::new));

        public Button {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(layout, "layout");
        }
    }

    /** Static text. */
    record Label(String text, LayoutSpec layout) implements Widget {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Label> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("text").forGetter(Label::text),
            LayoutSpec.CODEC.fieldOf("layout").forGetter(Label::layout)
        ).apply(instance, Label::new));

        public Label {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(layout, "layout");
        }
    }

    /** An item slot: a rectangle the cell binds to a slot index. */
    record Slot(int index, LayoutSpec layout) implements Widget {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Slot> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.INT.fieldOf("index").forGetter(Slot::index),
            LayoutSpec.CODEC.fieldOf("layout").forGetter(Slot::layout)
        ).apply(instance, Slot::new));

        public Slot {
            Objects.requireNonNull(layout, "layout");
            if (index < 0) {
                throw new IllegalArgumentException("Slot: index must be non-negative, got " + index);
            }
        }
    }

    /** A group of widgets laid out as a grid, so a quest board needs no manual coordinates. */
    record Group(List<Widget> children, int columns, int rowHeight, int columnWidth, LayoutSpec layout)
        implements Widget {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Group> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.lazyInitialized(() -> Widget.CODEC).listOf().fieldOf("children").forGetter(Group::children),
            Codec.INT.fieldOf("columns").forGetter(Group::columns),
            Codec.INT.fieldOf("rowHeight").forGetter(Group::rowHeight),
            Codec.INT.fieldOf("columnWidth").forGetter(Group::columnWidth),
            LayoutSpec.CODEC.fieldOf("layout").forGetter(Group::layout)
        ).apply(instance, Group::new));

        public Group {
            children = List.copyOf(Objects.requireNonNull(children, "children"));
            Objects.requireNonNull(layout, "layout");
            if (columns < 1 || rowHeight < 1 || columnWidth < 1) {
                throw new IllegalArgumentException("Group: columns, rowHeight and columnWidth must be positive, got "
                    + columns + "/" + rowHeight + "/" + columnWidth);
            }
        }
    }

    /** Single source of truth for every representation of this data (sub-02 §2). */
    Codec<Widget> CODEC = Codec.STRING.dispatch("kind", Widget::kind, Widget::codecFor);

    /** The {@code "kind"} discriminator an authored widget writes. */
    default String kind() {
        return getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    private static com.mojang.serialization.MapCodec<? extends Widget> codecFor(String kind) {
        return switch (kind) {
            case "button" -> Button.CODEC;
            case "label" -> Label.CODEC;
            case "slot" -> Slot.CODEC;
            case "group" -> Group.CODEC;
            default -> throw new IllegalArgumentException("unknown widget kind '" + kind
                + "' — expected button, label, slot or group");
        };
    }
}
