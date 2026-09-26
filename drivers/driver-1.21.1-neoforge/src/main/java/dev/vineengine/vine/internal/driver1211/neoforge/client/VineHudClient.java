package dev.vineengine.vine.internal.driver1211.neoforge.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.internal.driver1211.neoforge.boot.NeoForge1211Driver;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.ui.UiLayout;
import dev.vineengine.vine.registry.Holder;
import dev.vineengine.vine.ui.Anchor;
import dev.vineengine.vine.ui.HudLayer;
import dev.vineengine.vine.ui.LayoutSpec;

/**
 * The 1.21.1 NeoForge client half of sub-16's HUD layers: one loader render hook that draws
 * every registered {@link HudLayer} whose {@link HudLayer#defaultVisible()} holds, in
 * {@link HudLayer#zOrder()} order, at the anchor its descriptor names.
 *
 * <p><b>This cell draws, the engine decides.</b> The layer set, its order and its anchors are
 * descriptor data; the hook only asks and paints. A marker's rectangle comes from
 * {@link VineLayoutProbe#anchored}, which is the engine's own solver (sub-16 Stage A), so a
 * layer anchored {@code TOP_CENTER} sits in the same place here as on the Fabric cell without
 * either cell owning a copy of the anchor arithmetic. No Mixin is involved: NeoForge fires
 * {@link RenderGuiEvent.Post} from its own GUI layer manager once the vanilla HUD is drawn, and
 * a listener there is the whole hook.
 *
 * <p><b>What a layer is drawn as (v1, documented gap).</b> There is no layer <em>content</em>
 * model yet: a layer is an id, an anchor, an order and a visibility flag, and nothing describes
 * what it holds. So this hook draws the layer's id as a short text marker — enough to prove
 * per-player, client-side drawing, ordering and anchoring, and nothing more. Widget-bearing
 * layer content is an open piece of sub-16.
 *
 * <p><b>Per-player and client-side only.</b> This is a render hook in the client's own
 * distribution: nothing here is sent to a server, and no server code draws. Visibility is the
 * descriptor's default (there is no per-player override surface yet), so the filter is exactly
 * {@code defaultVisible}.
 *
 * <p><b>Ordering.</b> {@link HudLayer#zOrder()} ascending — higher draws later, which is what
 * "a health bar that lands under a chat line" is about. Ids break ties, so the same content
 * draws in the same order on every run.
 */
public final class VineHudClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(VineHudClient.class);

    /** Distance from the anchored edge, in the screen's logical pixels. */
    private static final int MARGIN = 2;

    /** A line between two markers stacked at the same anchor. */
    private static final int LINE_GAP = 1;

    /** Marker colour: opaque white, drawn with the engine's usual shadow. */
    private static final int MARKER_COLOR = 0xFFFFFFFF;

    /**
     * The layers to draw. Resolved on the first frame rather than at install: the client
     * entrypoint this hook is armed from may run before the common entrypoint that boots the
     * driver, and the layer set is descriptor content either way — frozen long before a frame.
     */
    private static List<HudLayer> layers;

    // Markers are a function of the screen size and the (frozen) layer set, so the per-frame
    // cost is a paint. A resize — the one thing that moves them — recomputes.
    private static int layoutWidth = -1;
    private static int layoutHeight = -1;
    private static List<Marker> layout = List.of();

    private VineHudClient() {
    }

    /** Wires the hook. Called from the client entrypoint, so a dedicated server never gets here. */
    public static void install() {
        NeoForge.EVENT_BUS.addListener(RenderGuiEvent.Post.class, VineHudClient::render);
        LOGGER.info("[VINE] HUD layer hook installed (client-dist only)");
    }

    private static void render(RenderGuiEvent.Post event) {
        Font font = Minecraft.getInstance().font;
        if (font == null) {
            return;
        }
        GuiGraphics guiGraphics = event.getGuiGraphics();
        int screenWidth = guiGraphics.guiWidth();
        int screenHeight = guiGraphics.guiHeight();
        for (Marker marker : markers(font, screenWidth, screenHeight)) {
            guiGraphics.drawString(font, marker.text(), marker.x(), marker.y(), MARKER_COLOR);
        }
    }

    /** The markers for this screen size, recomputed only when the size changes. */
    private static List<Marker> markers(Font font, int screenWidth, int screenHeight) {
        if (screenWidth == layoutWidth && screenHeight == layoutHeight) {
            return layout;
        }
        // Markers sharing an anchor stack in the order the layers draw, so two layers with the
        // same anchor do not land on top of each other.
        int[] placed = new int[Anchor.values().length];
        List<HudLayer> drawn = layers();
        List<Marker> markers = new ArrayList<>(drawn.size());
        for (HudLayer layer : drawn) {
            Component text = Component.literal(layer.id().path());
            int markerHeight = font.lineHeight;
            int offset = MARGIN + placed[layer.anchor().ordinal()]++ * (markerHeight + LINE_GAP);
            UiLayout.Rect rect = VineLayoutProbe.anchored(
                new LayoutSpec(0, offset, font.width(text), markerHeight, layer.anchor()), screenWidth, screenHeight);
            markers.add(new Marker(text, rect.x(), rect.y()));
        }
        layout = List.copyOf(markers);
        layoutWidth = screenWidth;
        layoutHeight = screenHeight;
        return layout;
    }

    /** The visible layers, resolved once and kept: structural descriptors freeze for the session. */
    private static List<HudLayer> layers() {
        List<HudLayer> resolved = layers;
        if (resolved == null) {
            resolved = registeredLayers();
            layers = resolved;
        }
        return resolved;
    }

    /**
     * Every registered {@code vine:hud_layer} descriptor with {@code defaultVisible} set, in draw
     * order. Read from the driver's structural view — the same snapshot the cell's content
     * materialization was handed, so JSON-authored layers (the testmod's {@code hunt_status})
     * and Java-registered ones are one list.
     */
    private static List<HudLayer> registeredLayers() {
        List<HudLayer> visible = new ArrayList<>();
        int hidden = 0;
        for (StructuralRegistryView.StructuralType type : NeoForge1211Driver.structuralView().types()) {
            if (type.type() != VineContent.HUD_LAYER_TYPE) {
                continue;
            }
            for (Holder<?> holder : type.entries()) {
                if (holder.value() instanceof HudLayer layer) {
                    if (layer.defaultVisible()) {
                        visible.add(layer);
                    } else {
                        hidden++;
                    }
                }
            }
        }
        visible.sort(Comparator.comparingInt(HudLayer::zOrder).thenComparing(HudLayer::id));
        LOGGER.info("[VINE] HUD layers: {} to draw (zOrder order), {} skipped as defaultVisible=false",
            visible.size(), hidden);
        return List.copyOf(visible);
    }

    /** One drawn marker: its text and where it goes, both fixed at layout time. */
    private record Marker(Component text, int x, int y) {
    }
}
