package dev.vineengine.vine.internal.driver1211.fabric.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.world.ServerWorld;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.entity.AttributeSpec;
import dev.vineengine.vine.internal.driver1211.fabric.data.FabricWorldView;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer;
import dev.vineengine.vine.internal.spi.EntityDriver;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 Fabric cell's {@link EntityDriver} (sub-08 Stage A): how the engine
 * puts one of its entities into a live world. Bound once during driver bootstrap,
 * the mirror of the storage and world-view seams' one-bind rule — a process has
 * exactly one cell, so it has exactly one entity primitive.
 *
 * <p><b>What crosses the seam:</b> engine ids, an engine position, a boolean. The
 * native {@link VineEntity} never leaves this class: the engine learns the spawn
 * happened from the return value and the logged live values, and Stage C's runtime
 * acts on the entity through the SPI's later primitives, not through this class.
 *
 * <p><b>Refusals are answers, not throws.</b> An unloaded dimension, a type this
 * cell never materialized and a refused world add all report {@code false} — the
 * SPI's contract — because each is an ordinary world condition (the engine's
 * descriptor check already ran before this class was asked).
 */
public final class FabricEntityDriver implements EntityDriver {

    private static final Logger LOG = LoggerFactory.getLogger(FabricEntityDriver.class);

    /**
     * Installs this cell's unload-side actor detach (sub-08 Stage C). Vanilla routes
     * a chunk unload straight through the final {@code Entity#setRemoved}, which
     * cannot be overridden, but Fabric's {@code ENTITY_UNLOAD} fires from the same
     * entity-handler {@code stopTracking} that removal and unload both pass through.
     * Detaching there keeps the engine's actor table free of bodies the world no
     * longer holds — one listener and no Mixin, Minimal Footprint.
     *
     * <p>Removal through {@code Entity#remove} is already handled by
     * {@link VineEntity#remove}; calling {@code vineDetach()} twice is harmless
     * because the engine's detach is idempotent.
     */
    public static void installUnloadDetach() {
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof VineEntity vine) {
                vine.vineDetach();
            }
        });
    }

    /**
     * Creates one entity of {@code entityId}'s native type in {@code dimensionId}'s
     * live world at {@code position}.
     *
     * <p>The entity is positioned before the world add, so what is added is what the
     * caller asked for rather than a default-positioned entity nudged afterwards; the
     * answer is then the world's own state, re-read by id.
     */
    @Override
    public boolean spawn(VineId entityId, VineId dimensionId, Vec3 position, java.util.UUID instance) {
        ServerWorld world = FabricWorldView.nativeWorld(dimensionId);
        if (world == null) {
            // No server yet, an id this cell's dimension registry does not know, or a
            // dimension it knows but has not loaded.
            LOG.info("vine: spawn refused {} in {} at {}: that dimension is not loaded in this cell",
                entityId, dimensionId, position.asString());
            return false;
        }
        EntityType<?> type = FabricContentMaterializer.entityTypeFor(entityId);
        if (type == null) {
            // The engine validated the descriptor before asking, so this means the
            // cell's own materialization missed a registered entity — reported as
            // refused rather than fabricated.
            LOG.info("vine: spawn refused {} in {}: this cell materialized no such entity type",
                entityId, dimensionId);
            return false;
        }
        Entity created = type.create(world);
        // The engine owns per-instance identity (sub-08 Stage C): the cell only carries
        // it, so a brain can be keyed by actor rather than by descriptor.
        if (!(created instanceof VineEntity entity)) {
            LOG.info("vine: spawn refused {} in {}: the native type created something other than a VINE actor",
                entityId, dimensionId);
            return false;
        }
        entity.vineInstance(instance);
        entity.refreshPositionAndAngles(position.x(), position.y(), position.z(), 0.0F, 0.0F);
        if (!world.spawnEntity(entity) || world.getEntityById(entity.getId()) != entity) {
            // The add was refused (unloaded chunk, duplicate identity, a world that
            // takes no entities): nothing of this descriptor exists there afterwards,
            // which is exactly what the caller asked.
            LOG.info("vine: spawn refused {} in {} at {}: the world add did not take (chunk {} {} loaded: {})",
                entityId, dimensionId, position.asString(),
                (int) Math.floor(position.x()) >> 4, (int) Math.floor(position.z()) >> 4,
                world.isChunkLoaded((int) Math.floor(position.x()) >> 4, (int) Math.floor(position.z()) >> 4));
            return false;
        }
        LOG.info("vine: entity spawned {} {}", entityId, FabricContentMaterializer
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
        RegistryEntry<EntityAttribute> attribute = FabricContentMaterializer.attributeEntry(spec.attribute());
        EntityAttributeInstance instance = entity.getAttributeInstance(attribute);
        if (instance == null) {
            throw new IllegalStateException("spawned entity " + entity.vineId() + " has no instance for "
                + spec.attribute() + " — its default attribute container was not registered with it");
        }
        return instance.getValue();
    }
}
