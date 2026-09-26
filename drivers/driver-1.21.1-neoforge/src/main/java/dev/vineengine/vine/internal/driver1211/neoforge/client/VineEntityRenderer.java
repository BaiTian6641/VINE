package dev.vineengine.vine.internal.driver1211.neoforge.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

import dev.vineengine.vine.internal.driver1211.neoforge.entity.VineEntity;

/**
 * The renderer every materialized engine entity needs on the client (the client half of
 * {@code NeoForgeContentMaterializer#registerEntities}, sub-08 Stage A).
 *
 * <p><b>Why it must exist even though it draws nothing.</b> Vanilla's {@code LevelRenderer}
 * walks every client-visible entity and calls {@code EntityRenderDispatcher#shouldRender},
 * which dereferences the entity's renderer without a null check — an engine entity type
 * registered without a renderer therefore crashes the client the moment one is spawned, not
 * the moment it is registered. Having a renderer is what makes a spawned engine entity a
 * well-defined client entity.
 *
 * <p><b>Why it draws nothing.</b> An engine entity's visual — model, animation, part bodies —
 * is the render/player-animation phase's work and the engine has not defined one yet;
 * inventing a placeholder shape here would pin a look nothing authored. Until that phase
 * lands, the honest client state is "present, correctly sized and tracked, not yet drawn".
 */
public final class VineEntityRenderer extends EntityRenderer<VineEntity> {

    /**
     * A real vanilla texture: nothing samples it while nothing is drawn, but a renderer whose
     * texture location did not resolve would log a missing-texture warning the day drawing
     * starts, and this one never will.
     */
    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public VineEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(VineEntity entity) {
        return TEXTURE;
    }
}
