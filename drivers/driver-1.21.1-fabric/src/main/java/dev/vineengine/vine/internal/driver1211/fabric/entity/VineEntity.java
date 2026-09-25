package dev.vineengine.vine.internal.driver1211.fabric.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import dev.vineengine.vine.entity.AttributeSpec;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer;
import dev.vineengine.vine.registry.VineId;

/**
 * The 1.21.1 Fabric cell's native entity carrier (sub-08 Stage A): one vanilla
 * {@link PathAwareEntity} per materialized {@code vine:entity} descriptor, carrying
 * the engine id and descriptor it was created from. The engine never sees this
 * class — {@code EntityDriver.spawn} creates it and reports the engine values
 * back — so a native type stays inside the driver jar.
 *
 * <p><b>No native AI.</b> {@link #initGoals} installs nothing: behaviour is
 * engine-owned from the start (Stage C wires the engine's own scheduler), and
 * mapping {@code VineBrain} onto vanilla goals is forbidden (§5.13). A mobile but
 * goal-less mob is this stage's correct shape, not a gap.
 *
 * <p><b>Attributes.</b> Base values ride the type's
 * {@code DefaultAttributeContainer}, registered with the type by
 * {@link FabricContentMaterializer#registerEntities}; the descriptor's modifiers
 * are installed here, on the instance, because vanilla's container builder has no
 * modifier slots. A modifier an attribute instance cannot carry (no instance for a
 * declared attribute) fails loudly rather than being skipped.
 *
 * <p><b>Invariants:</b> the id is the descriptor's id — the materializer checks the
 * holder agrees before either reaches here; the descriptor is the exact immutable
 * one the type was materialized for. Neither field changes for the entity's
 * lifetime.
 */
public final class VineEntity extends PathAwareEntity {

    private final VineId vineId;
    private final EntityDescriptor vineDescriptor;

    /**
     * Creates the carrier for {@code descriptor}. Called only from the type's
     * factory ({@link FabricContentMaterializer#registerEntities}), which is the
     * one place both arguments are in scope.
     */
    public VineEntity(EntityType<? extends VineEntity> type, World world, VineId id, EntityDescriptor descriptor) {
        super(type, world);
        this.vineId = id;
        this.vineDescriptor = descriptor;
        installModifiers(descriptor);
    }

    @Override
    protected void initGoals() {
        // Engine-owned behaviour (§5.13): the engine's runtime drives this entity;
        // no vanilla goal may act on it behind the engine's back.
    }

    /** The engine id this entity is an instance of. */
    public VineId vineId() {
        return vineId;
    }

    /** The descriptor this entity's identity, size and attributes came from. */
    public EntityDescriptor vineDescriptor() {
        return vineDescriptor;
    }

    /**
     * Installs every descriptor modifier on its attribute instance, in declaration
     * order. Persistent, so a later equipment or effect change cannot silently
     * replace a value the descriptor author asked for.
     *
     * <p>The attribute's instance must exist: the type's own default container was
     * registered with every attribute the descriptor names, so a missing one is a
     * broken boot invariant, never a world condition.
     */
    private void installModifiers(EntityDescriptor descriptor) {
        boolean anyModifier = false;
        for (AttributeSpec spec : descriptor.attributes()) {
            if (spec.modifiers().isEmpty()) {
                continue;
            }
            RegistryEntry<EntityAttribute> attribute = FabricContentMaterializer.attributeEntry(spec.attribute());
            EntityAttributeInstance instance = this.getAttributeInstance(attribute);
            if (instance == null) {
                throw new IllegalStateException("entity " + descriptor.id() + ": attribute " + spec.attribute()
                    + " has no instance on a fresh entity — its default container was not registered with it");
            }
            for (dev.vineengine.vine.entity.AttributeModifier modifier : spec.modifiers()) {
                instance.addPersistentModifier(new EntityAttributeModifier(
                    Identifier.of(modifier.id().namespace(), modifier.id().path()),
                    modifier.amount(), operation(modifier.operation())));
            }
            anyModifier = true;
        }
        if (anyModifier) {
            // Vanilla's LivingEntity constructor set health from the pre-modifier
            // max health; re-derive it so a descriptor that moves max health does
            // not leave the entity at the value it had before its own modifiers.
            this.setHealth(this.getMaxHealth());
        }
    }

    /**
     * The native operation for an engine one. Exhaustive over the engine enum: the
     * three operations the descriptor language names are exactly the three vanilla
     * exposes, and a fourth engine operation would fail this cell's compilation
     * rather than be skipped at runtime.
     */
    private static EntityAttributeModifier.Operation operation(
            dev.vineengine.vine.entity.AttributeModifier.Operation operation) {
        return switch (operation) {
            case ADD -> EntityAttributeModifier.Operation.ADD_VALUE;
            case MULTIPLY_BASE -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case MULTIPLY_TOTAL -> EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        };
    }
}
