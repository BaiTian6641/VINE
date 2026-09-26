package dev.vineengine.vine.internal.driver1211.fabric.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.ui.UiLayout;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.ui.ScreenDescriptor;
import dev.vineengine.vine.ui.Widget;

/**
 * The 1.21.1 Fabric client half of sub-16's screen materialization: a {@link ScreenDescriptor}
 * becomes a vanilla {@link Screen} whose widgets sit where the engine's solver puts them.
 *
 * <p><b>The engine lays it out; this class only draws.</b> Every rectangle comes from
 * {@link UiLayout#resolve} (a button, under its id) or from {@link VineLayoutProbe} (a label or
 * a slot, which the public report does not name) — the same solver either way, so a screen lands
 * in the same place on this cell, on the NeoForge cell and in the {@code ui.layout.txt} golden.
 * There is no layout arithmetic here to drift.
 *
 * <p><b>Logical pixels.</b> A vanilla screen's {@code width}/{@code height} are the GUI-scaled
 * units the solver expects (sub-16 Stage A's correction), so {@code init()} passes them straight
 * through. The GUI scale is never read: a cell that scales is a cell that is right at scale 2
 * and off the screen at scale 3.
 *
 * <p><b>What a press does.</b> A press has no engine-side meaning yet — the data behind a screen
 * is server-owned menu state, and sub-16 Stage F has not landed — so this class logs the widget
 * that was pressed and invents nothing. In particular it sends nothing to the server: this half
 * is drawing and input only.
 *
 * <p><b>Widget mapping (v1).</b> {@link Widget.Button} → a vanilla {@code ButtonWidget};
 * {@link Widget.Label} → shadowed text; {@link Widget.Slot} → a bordered box (there is no menu
 * container to bind a real slot to yet, so the rectangle is the honest v1); {@link Widget.Group}
 * → its children, each at the rectangle the solver gave it.
 *
 * <p><b>Client-dist only.</b> Referenced from {@link ClientScriptRunner} and from nowhere on a
 * dedicated server; {@code VineFabricClient} is the {@code "client"} entrypoint, so the class is
 * loaded only where a client exists.
 */
public final class VineScreensClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(VineScreensClient.class);

    /** Slot chrome: a plain box until a menu container exists (documented gap). */
    private static final int SLOT_FILL = 0xFF373737;
    private static final int SLOT_BORDER = 0xFF8B8B8B;

    /** Label text colour — vanilla's HUD white, with the shadow the engine adds. */
    private static final int LABEL_COLOR = 0xFFE0E0E0;

    private VineScreensClient() {
    }

    /**
     * The native screen for {@code descriptor}, laid out by the engine at whatever size the
     * client reports when the screen opens (and again on every resize).
     */
    public static Screen materialize(ScreenDescriptor descriptor) {
        return new DescriptorScreen(descriptor);
    }

    /** A descriptor rendered as vanilla widgets; nothing of the descriptor's shape is decided here. */
    private static final class DescriptorScreen extends Screen {

        private final ScreenDescriptor descriptor;
        private final List<Decoration> decorations = new ArrayList<>();

        private DescriptorScreen(ScreenDescriptor descriptor) {
            super(Text.literal(descriptor.id().toString()));
            this.descriptor = descriptor;
        }

        /**
         * Rebuilds the widget set for the current size. Vanilla calls this again on every resize,
         * so both the children and the resolved rectangles start from nothing — a screen that
         * merely added its widgets here would double them at the first window drag.
         */
        @Override
        protected void init() {
            clearChildren();
            decorations.clear();
            Map<VineId, UiLayout.Rect> rects = UiLayout.resolve(descriptor, width, height);
            materialize(descriptor.root(), rects);
        }

        /** The descriptor's own pause flag (sub-16 §2): content decides, the cell obeys. */
        @Override
        public boolean shouldPause() {
            return descriptor.pausesGame();
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            for (Decoration decoration : decorations) {
                decoration.draw(context, textRenderer);
            }
        }

        private void materialize(Widget widget, Map<VineId, UiLayout.Rect> rects) {
            switch (widget) {
                case Widget.Button button -> {
                    UiLayout.Rect rect = require(rects.get(button.id()), button.id());
                    addDrawableChild(ButtonWidget.builder(Text.literal(button.label()), pressed -> pressed(button))
                        .dimensions(rect.x(), rect.y(), rect.width(), rect.height())
                        .build());
                }
                case Widget.Label label -> decorations.add(new TextDecoration(
                    VineLayoutProbe.rectOf(descriptor, label, width, height), Text.literal(label.text())));
                case Widget.Slot slot -> decorations.add(new SlotDecoration(
                    VineLayoutProbe.rectOf(descriptor, slot, width, height)));
                case Widget.Group group -> group.children().forEach(child -> materialize(child, rects));
            }
        }

        private static UiLayout.Rect require(UiLayout.Rect rect, VineId id) {
            if (rect == null) {
                throw new IllegalStateException("the engine's layout solver reported no rectangle for widget " + id);
            }
            return rect;
        }

        private void pressed(Widget.Button button) {
            LOGGER.info("[VINE] screen {}: button {} ('{}') pressed — no engine menu state to act on yet"
                + " (sub-16 Stage F); nothing sent", descriptor.id(), button.id(), button.label());
        }
    }

    /** Something a screen draws that is not a vanilla widget: text or a slot box. */
    private interface Decoration {

        void draw(DrawContext context, TextRenderer textRenderer);
    }

    private record TextDecoration(UiLayout.Rect rect, Text text) implements Decoration {

        @Override
        public void draw(DrawContext context, TextRenderer textRenderer) {
            context.drawTextWithShadow(textRenderer, text, rect.x(), rect.y(), LABEL_COLOR);
        }
    }

    private record SlotDecoration(UiLayout.Rect rect) implements Decoration {

        @Override
        public void draw(DrawContext context, TextRenderer textRenderer) {
            context.fill(rect.x(), rect.y(), rect.x() + rect.width(), rect.y() + rect.height(), SLOT_FILL);
            context.drawBorder(rect.x(), rect.y(), rect.width(), rect.height(), SLOT_BORDER);
        }
    }
}
