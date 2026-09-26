package dev.vineengine.vine.internal.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.ui.LayoutSpec;
import dev.vineengine.vine.ui.ScreenDescriptor;
import dev.vineengine.vine.ui.Widget;

/**
 * The engine's layout solver (sub-16 Stage A): logical rectangles in, absolute pixel
 * rectangles out, for one screen size and one GUI scale.
 *
 * <p>Pure on purpose. A layout a cell computes is a layout that differs between cells, and a
 * widget's hit rectangle drifting from its drawn rectangle is the classic way a menu becomes
 * unusable on exactly one loader. Here the arithmetic is a function of the descriptor and the
 * screen, so the golden fixture pins it and both cells agree by construction.
 *
 * <p><b>Everything here is logical pixels.</b> A cell reports the screen size in the same
 * units a vanilla screen is laid out in (its "GUI-scaled" width and height), so the solver
 * never sees a physical pixel and the scale is not an input at all. Scaling inside the
 * solver is the classic way a menu is correct at scale 2 and off the screen at scale 3.
 */
public final class UiLayout {

    /** One widget's resolved rectangle, in absolute pixels. */
    public record Rect(int x, int y, int width, int height) {

        /** Whether {@code (px, py)} is inside this rectangle. */
        public boolean contains(int px, int py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    private UiLayout() {
    }

    /**
     * Resolves every widget in {@code screen} against a screen of {@code width} x {@code height}
     * <em>logical</em> pixels — the size a cell's own screen API reports.
     */
    public static Map<VineId, Rect> resolve(ScreenDescriptor screen, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("layout needs a positive screen size, got " + width + "x" + height);
        }
        Map<VineId, Rect> out = new LinkedHashMap<>();
        walk(screen.root(), 0, 0, width, height, out);
        return out;
    }

    /** Resolves against the fixture's own frame: 320x240 logical pixels. */
    public static Map<VineId, Rect> resolveFixtureFrame(ScreenDescriptor screen) {
        return resolve(screen, 320, 240);
    }

    private static void walk(Widget widget, int originX, int originY, int screenWidth, int screenHeight,
            Map<VineId, Rect> out) {
        Rect rect = place(widget.layout(), originX, originY, screenWidth, screenHeight);
        switch (widget) {
            case Widget.Button button -> out.put(button.id(), rect);
            case Widget.Slot slot -> out.put(VineId.of("vine", "slot_" + slot.index()), rect);
            case Widget.Label ignored -> {
                // A label has no id: it is text, not something a player interacts with.
            }
            case Widget.Group group -> {
                int index = 0;
                for (Widget child : group.children()) {
                    int column = index % group.columns();
                    int row = index / group.columns();
                    walk(child,
                        rect.x() + column * group.columnWidth(),
                        rect.y() + row * group.rowHeight(),
                        screenWidth, screenHeight, out);
                    index++;
                }
            }
        }
    }

    /** One rectangle, in logical pixels. */
    static Rect place(LayoutSpec spec, int originX, int originY, int screenWidth, int screenHeight) {
        int width = spec.width();
        int height = spec.height();
        int offsetX = spec.x();
        int offsetY = spec.y();
        int x = switch (spec.anchor()) {
            case TOP_LEFT, BOTTOM_LEFT -> originX + offsetX;
            case TOP_CENTER, CENTER -> originX + (screenWidth - width) / 2 + offsetX;
            case TOP_RIGHT -> originX + screenWidth - width - offsetX;
        };
        int y = switch (spec.anchor()) {
            case TOP_LEFT, TOP_CENTER, TOP_RIGHT -> originY + offsetY;
            case BOTTOM_LEFT -> originY + screenHeight - height - offsetY;
            case CENTER -> originY + (screenHeight - height) / 2 + offsetY;
        };
        return new Rect(x, y, width, height);
    }

    /** Every interactive widget id a screen declares, in layout order — a probe for tests. */
    public static List<VineId> widgetIds(ScreenDescriptor screen) {
        List<VineId> ids = new ArrayList<>();
        collect(screen.root(), ids);
        return List.copyOf(ids);
    }

    private static void collect(Widget widget, List<VineId> ids) {
        if (widget instanceof Widget.Button button) {
            ids.add(button.id());
        } else if (widget instanceof Widget.Group group) {
            group.children().forEach(child -> collect(child, ids));
        }
    }
}
