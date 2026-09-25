package dev.vineengine.vine.internal.driver1211.neoforge.entity;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.entity.AttributeSpec;
import dev.vineengine.vine.internal.driver1211.neoforge.data.NeoForgeWorldView;
import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeContentMaterializer;
import dev.vineengine.vine.internal.spi.EntityDriver;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 NeoForge cell's {@link EntityDriver} (sub-08 Stage A): how the engine
 * puts one of its entities into a live world. Bound once during driver bootstrap,
 * the mirror of the storage and world-view seams' one-bind rule — a process has
 * exactly one cell, so it has exactly one entity primitive.
 *
 * <p><b>What crosses the seam:</b> engine ids, an engine position, a boolean. The
 * native {@link VineEntity} never leaves this class: the engine learns the spawn
 * happened from the return value and the logged live values, and Stage C's runtime
 * acts on the entity through the SPI's later primitives, not through this class.
 *
 * <p><b>Dimension resolution is shared, not re-derived:</b> the live level for an
 * engine dimension comes from {@link NeoForgeWorldView}'s one answer to that
 * question — the entity primitive and the block-state view must agree on which
 * level an id names.
 *
 * <p><b>Refusals are answers, not throws.</b> An unloaded dimension, a type this
 * cell never materialized and a refused world add all report {@code false} — the
 * SPI's contract — because each is an ordinary world condition (the engine's
 * descriptor check already ran before this class was asked).
 */
public final class NeoForgeEntityDriver implements EntityDriver {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeEntityDriver.class);

    /**
     * Creates one entity of {@code entityId}'s native type in {@code dimensionId}'s
     * live level at {@code position}.
     *
     * <p>The entity is positioned before the world add, so what is added is what the
     * caller asked for rather than a default-positioned entity nudged afterwards; the
     * answer is then the level's own state, re-read by id.
     */
    @Override
    public boolean spawn(VineId entityId, VineId dimensionId, Vec3 position) {
        ServerLevel level = NeoForgeWorldView.nativeLevel(dimensionId);
        if (level == null) {
            // No server yet, an id this cell's dimension registry does not know, or a
            // dimension it knows but has not loaded.
            return false;
        }
        EntityType<?> type = NeoForgeContentMaterializer.entityTypeFor(entityId);
        if (type == null) {
            // The engine validated the descriptor before asking, so this means the
            // cell's own materialization missed a registered entity — reported as
            // refused rather than fabricated.
            return false;
        }
        Entity created = type.create(level);
        if (!(created instanceof VineEntity entity)) {
            return false;
        }
        entity.moveTo(position.x(), position.y(), position.z(), 0.0F, 0.0F);
        if (!level.addFreshEntity(entity) || level.getEntity(entity.getId()) != entity) {
            // The add was refused (unloaded chunk, duplicate identity, a level that
            // takes no entities): nothing of this descriptor exists there afterwards,
            // which is exactly what the caller asked.
            return false;
        }
        LOG.info("vine: entity spawned {} {}", entityId, NeoForgeContentMaterializer
            .describeAttributes(entity.vineDescriptor().attributes(), spec -> liveValue(entity, spec)));
        return true;
    }

    /**
     * The value the spawned entity's own attribute instance holds — the read-back
     * that proves the descriptor's base values and modifiers reached the native
     * entity, not merely the registration call. A missing instance is a broken cell
     * invariant (the type's container was registered with every declared attribute),
     * never an ordinary world condition.
     */
    private static double liveValue(VineEntity entity, AttributeSpec spec) {
        Holder<Attribute> attribute = NeoForgeContentMaterializer.attributeHolder(spec.attribute());
        AttributeInstance instance = entity.getAttribute(attribute);
        if (instance == null) {
            throw new IllegalStateException("spawned entity " + entity.vineId() + " has no instance for "
                + spec.attribute() + " — its default attribute container was not registered with it");
        }
        return instance.getValue();
    }
}
