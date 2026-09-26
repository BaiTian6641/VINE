package dev.vineengine.vine.internal.driver1211.fabric.client;

import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.EmptyEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.driver1211.fabric.entity.VinePartEntity;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer;

/**
 * Client renderers for the engine's materialized entity types (sub-08 Stage A's client
 * half). Wired from {@code VineFabricClient}, so a dedicated server never reaches it.
 *
 * <p><b>Why this is not optional.</b> Vanilla's render walk looks a renderer up per entity and
 * dereferences it, so an entity type with no registered renderer does not draw blankly — it
 * takes the whole client down with an {@code NullPointerException} inside
 * {@code EntityRenderDispatcher.shouldRender}. That is exactly what happened the first time an
 * engine entity was in view of the scripted client run ({@code tck_parts_spawn}): the engine's
 * entity existed, the client tracked it, and the client died rendering it.
 *
 * <p><b>Why empty.</b> An engine entity's visual materialization — a GeckoLib model, a part
 * rig — is a later piece of work; until it lands, vanilla's own {@link EmptyEntityRenderer}
 * (the renderer for a type that exists without a model, as markers use) is the honest
 * placeholder. It draws nothing and leaves the rest of the world drawing, which is the state
 * the engine is actually in. A real renderer replaces this registration and nothing else in
 * the cell assumes the empty one.
 */
public final class VineEntityRenderers {

    private static final Logger LOGGER = LoggerFactory.getLogger(VineEntityRenderers.class);

    private VineEntityRenderers() {
    }

    /** Registers an empty renderer for every engine entity type this cell materialized. */
    public static void register() {
        int registered = 0;
        for (EntityType<?> type : FabricContentMaterializer.materializedEntityTypes()) {
            registerEmpty(type);
            registered++;
        }
        EntityType<VinePartEntity> parts = FabricContentMaterializer.partEntityType();
        if (parts != null) {
            registerEmpty(parts);
            registered++;
        }
        LOGGER.info("[VINE] client renderers: {} empty renderer(s) for engine entity types (their visual"
            + " materialization is still to come)", registered);
    }

    private static <E extends Entity> void registerEmpty(EntityType<? extends E> type) {
        EntityRendererRegistry.register(type, EmptyEntityRenderer::new);
    }
}
