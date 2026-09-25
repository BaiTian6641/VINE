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
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer;
import dev.vineengine.vine.internal.entity.EntityRuntime;
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
 * <p><b>Driving the engine's loop (sub-08 Stage C).</b> {@link #tick()} hands this
 * body to {@link EntityRuntime#tickActor} once per server tick, with a per-actor
 * {@link FabricEntityPrimitives} built once and cached, so an unattached entity
 * pays one identity check per tick and nothing else. The engine owns the decision
 * (whether a brain is attached, and what it does); this class owns the body
 * (position, navigation, sight). A removed or unloaded entity detaches itself —
 * {@link #remove} covers the world's explicit removals and the driver's
 * {@code ServerEntityEvents.ENTITY_UNLOAD} hook covers chunk unload, which vanilla
 * routes through the final {@code setRemoved} and cannot be overridden — so a
 * brain never outlives the body it was driving.
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

    /** The engine's per-instance identity (sub-08 Stage C); set by the spawn path. */
    private java.util.UUID vineInstance;

    /** This actor's engine identity, built once on the first tick and reused (no per-tick allocation). */
    private VineEntityRef actorRef;

    /** The per-actor world primitives the engine's loop is driven with; built alongside {@link #actorRef}. */
    private FabricEntityPrimitives actorPrimitives;

    /** Tags this entity with its engine instance id — called only by the cell's spawn path. */
    public void vineInstance(java.util.UUID instance) {
        this.vineInstance = java.util.Objects.requireNonNull(instance, "instance");
    }

    /** The engine's instance id for this entity, or {@code null} before the spawn path sets it. */
    public java.util.UUID vineInstance() {
        return vineInstance;
    }

    /**
     * Drives the engine's actor for this body (sub-08 Stage C): one callback per
     * server tick, handing the engine this actor's primitives. Nothing is attached
     * for a plain entity, and {@link EntityRuntime#tickActor} answers that case
     * without work, so Stage A's behaviour is unchanged — the only cost is the
     * instance-id check below.
     */
    @Override
    public void tick() {
        super.tick();
        if (this.vineInstance == null) {
            // Never tagged by the engine's spawn path (or a client-side copy of a
            // spawned entity): there is no actor to drive.
            return;
        }
        EntityRuntime.tickActor(actorRef(), actorPrimitives());
    }

    /**
     * Detaches this body's brain when the world removes it, so a removed actor
     * never keeps being ticked by a brain it no longer has a body for. Chunk unload
     * does not pass through here (vanilla's {@code setRemoved} is final); the
     * driver's unload hook covers that path with the same
     * {@link #vineDetach()} call.
     */
    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        vineDetach();
    }

    /**
     * Drops whatever brain is attached to this actor; a no-op for an untagged body
     * and idempotent, so the removal and unload hooks may both call it.
     */
    void vineDetach() {
        if (this.vineInstance != null) {
            EntityRuntime.detach(actorRef());
        }
    }

    /** This actor's engine identity, built lazily once — the instance id is fixed at spawn. */
    private VineEntityRef actorRef() {
        VineEntityRef ref = this.actorRef;
        if (ref == null) {
            this.actorRef = ref = new VineEntityRef(this.vineId, this.vineInstance);
        }
        return ref;
    }

    /** The primitives serving this actor, built lazily once and reused every tick. */
    private FabricEntityPrimitives actorPrimitives() {
        FabricEntityPrimitives primitives = this.actorPrimitives;
        if (primitives == null) {
            this.actorPrimitives = primitives = new FabricEntityPrimitives(this);
        }
        return primitives;
    }
}
