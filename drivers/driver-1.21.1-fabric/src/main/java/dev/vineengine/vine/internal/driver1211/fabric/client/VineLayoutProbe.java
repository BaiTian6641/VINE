package dev.vineengine.vine.internal.driver1211.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.vineengine.vine.internal.ui.UiLayout;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.ui.LayoutSpec;
import dev.vineengine.vine.ui.ScreenDescriptor;
import dev.vineengine.vine.ui.Widget;

/**
 * The cell's door onto the engine's layout solver for the nodes the public report does not
 * name (sub-16 Stage B, client half).
 *
 * <p>{@link UiLayout#resolve} answers with rectangles for the widgets a player interacts with —
 * a button under its id, a slot under its id — because those are the ones a consumer hit-tests.
 * A label has no id, and a HUD layer's marker has no {@code ScreenDescriptor} at all, so
 * neither has a public rectangle. The one thing this cell must not do is derive one itself:
 * anchor arithmetic copied into a cell is the arithmetic that drifts, and the engine's solver
 * exists exactly so two cells place the same widget in the same spot (the {@code ui.layout.txt}
 * golden pins that).
 *
 * <p>So this class asks the same solver again. It hands {@link UiLayout#resolve} a tree in which
 * the node of interest stands in a probe button and reads the probe's rectangle: the engine's
 * own walk places it, ancestors, grid cell and anchor included. A single-widget screen does the
 * same for a marker that has no tree to belong to. Nothing here computes a coordinate.
 *
 * <p><b>Cost.</b> One resolve per node that needs it, at screen {@code init()} (open or resize)
 * and once per HUD size — never per frame. Screens are authored data, not per-frame state.
 */
final class VineLayoutProbe {

    /**
     * The id the probe button registers under. Reserved: a screen that authors a widget with it
     * would make the probe's own rectangle ambiguous, so that is refused rather than resolved
     * by guessing which entry won.
     */
    private static final VineId PROBE = VineId.of("vine", "__cell_layout_probe");

    private VineLayoutProbe() {
    }

    /**
     * The rectangle the engine's solver gives {@code target} as a node of {@code screen}'s tree,
     * for a widget {@link UiLayout#resolve} does not report (a label, a slot).
     *
     * @throws IllegalStateException when {@code target} is not a node of {@code screen}, or when
     *     the solver returns no rectangle for the probe — both mean the tree and the descriptor
     *     disagree, which is a bug this cell must report rather than draw around
     */
    static UiLayout.Rect rectOf(ScreenDescriptor screen, Widget target, int width, int height) {
        if (UiLayout.widgetIds(screen).contains(PROBE)) {
            throw new IllegalStateException("screen " + screen.id() + " declares a widget named " + PROBE
                + ", which this cell's layout probe reserves");
        }
        Widget root = replace(screen.root(), target);
        if (root == null) {
            throw new IllegalStateException("widget " + target + " is not a node of screen " + screen.id());
        }
        return resolveOne(new ScreenDescriptor(screen.id(), root, screen.pausesGame()), width, height);
    }

    /**
     * The rectangle the solver gives a widget measured against the screen itself — how a HUD
     * marker (which carries an {@link dev.vineengine.vine.ui.Anchor} and nothing else) is placed
     * without the cell repeating the anchor arithmetic.
     */
    static UiLayout.Rect anchored(LayoutSpec spec, int width, int height) {
        return resolveOne(new ScreenDescriptor(PROBE, new Widget.Button(PROBE, "", spec), false), width, height);
    }

    private static UiLayout.Rect resolveOne(ScreenDescriptor probe, int width, int height) {
        Map<VineId, UiLayout.Rect> rects = UiLayout.resolve(probe, width, height);
        UiLayout.Rect rect = rects.get(PROBE);
        if (rect == null) {
            throw new IllegalStateException("the engine's layout solver returned no rectangle for the probe widget");
        }
        return rect;
    }

    /**
     * {@code target}'s node with the probe button in its place, or {@code null} when the target
     * is not in the tree. Siblings are kept as they are: a group child's grid cell is its index,
     * so dropping a sibling would move the node being placed.
     */
    private static Widget replace(Widget widget, Widget target) {
        if (widget == target) {
            return new Widget.Button(PROBE, "", target.layout());
        }
        if (widget instanceof Widget.Group group) {
            List<Widget> children = null;
            for (int i = 0; i < group.children().size(); i++) {
                Widget replaced = replace(group.children().get(i), target);
                if (replaced != null) {
                    if (children == null) {
                        children = new ArrayList<>(group.children());
                    }
                    children.set(i, replaced);
                }
            }
            if (children != null) {
                return new Widget.Group(children, group.columns(), group.rowHeight(), group.columnWidth(),
                    group.layout());
            }
        }
        return null;
    }
}
