package dev.vineengine.vine.internal.driver1211.fabric.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.entity.AttributeSpec;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer;
import dev.vineengine.vine.internal.entity.EntityRuntime;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

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
 * <p><b>Hosting the engine's parts (sub-08 Stage D).</b> A descriptor that declares
 * parts gets one more call per server tick: {@link EntityRuntime#tickParts} with this
 * actor's {@link FabricPartHost}, which is what tells the engine where this body stands
 * and hands it the actor's persistent state tree. A descriptor with no parts pays a
 * single emptiness check and nothing else. The native half is one {@link VinePartEntity}
 * per declared part, created on the first server tick and kept where the engine's
 * computed box puts it — the engine owns the geometry, this class owns the bodies that
 * carry it (and the {@code VinePartEntity} javadoc states what a part may never be).
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

    /** This actor's part host (sub-08 Stage D); built once and handed to the engine every tick. */
    private FabricPartHost partHost;

    /**
     * The native bodies carrying this actor's parts, or {@code null} until the first
     * server tick that declares parts (sub-08 Stage D). Fixed once built: the descriptor
     * cannot change for this entity's lifetime.
     */
    private List<VinePartEntity> partBodies;

    /**
     * Live parts-declaring actors by engine ref (sub-08 Stage D): the routing table the
     * part delta transport resolves a ref through, because only a body can say which
     * players are tracking it. An actor joins it when its part host is built (its first
     * server tick) and leaves it in {@link #vineDetach()} — the one place both the
     * removal and the chunk-unload path pass through — so a delta for a gone actor is
     * simply not sent, and no other entity ever pays for this table.
     */
    private static final Map<VineEntityRef, VineEntity> LIVE = new ConcurrentHashMap<>();

    /** The live body for {@code ref}, or {@code null} when its actor is gone or unloaded. */
    public static VineEntity bodyFor(VineEntityRef ref) {
        return LIVE.get(ref);
    }

    /** Tags this entity with its engine instance id — called only by the cell's spawn path. */
    public void vineInstance(java.util.UUID instance) {
        this.vineInstance = java.util.Objects.requireNonNull(instance, "instance");
    }

    /** The engine's instance id for this entity, or {@code null} before the spawn path sets it. */
    public java.util.UUID vineInstance() {
        return vineInstance;
    }

    /**
     * The engine's identity for this body, or {@code null} while the spawn path has not
     * tagged it. This is what the rest of the cell asks a target: an entity with a ref is a
     * VINE actor whose fight the engine owns, and one without is vanilla's (sub-10 Stage D
     * scopes its combat hooks by exactly this answer — Minimal Footprint §5.1).
     */
    public VineEntityRef vineRef() {
        return this.vineInstance == null ? null : actorRef();
    }

    /**
     * Drives the engine's actor for this body (sub-08 Stage C): one callback per
     * server tick, handing the engine this actor's primitives. Nothing is attached
     * for a plain entity, and {@link EntityRuntime#tickActor} answers that case
     * without work, so Stage A's behaviour is unchanged — the only cost is the
     * instance-id check below.
     *
     * <p>A descriptor that declares parts (sub-08 Stage D) gets
     * {@link EntityRuntime#tickParts} afterwards, then has its bodies placed on the
     * boxes the engine computed. The guard is the descriptor's own part list, so a
     * single-collider entity pays one emptiness check per tick and never touches a
     * part host at all.
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
        if (this.vineDescriptor.parts().isEmpty()) {
            return;
        }
        EntityRuntime.tickParts(actorRef(), partHost());
        if (!getWorld().isClient()) {
            // The server owns the bodies: a client-side copy of the actor has the id
            // but no engine actor, and the engine is what says where a part stands.
            drivePartBodies();
        }
    }

    /**
     * Creates this actor's part bodies once, then keeps each one on the box the engine
     * computed this tick (sub-08 Stage D). One {@link VineParts#box} per part is the
     * whole algorithm: the engine owns the geometry, and the box's centre is the
     * position its body carries (see {@link VinePartEntity#drive}).
     */
    private void drivePartBodies() {
        List<VinePartEntity> bodies = this.partBodies;
        if (bodies == null) {
            bodies = this.partBodies = createPartBodies();
        }
        if (!EntityRuntime.hasParts(actorRef())) {
            // No clip is playing yet: the engine has no pose, so it has no box to place
            // a body on. The bodies wait on the actor where they were created, rather
            // than being given a position this cell invented.
            return;
        }
        VineEntityRef ref = actorRef();
        // The host's own transform, read once: the same position and yaw the engine was
        // handed this tick, so a box and the body carrying it cannot disagree.
        Vec3 position = partHost().position();
        float yaw = partHost().yawDegrees();
        for (VinePartEntity body : bodies) {
            Optional<OrientedBox> box = VineParts.box(ref, body.partName(), position, yaw);
            if (box.isPresent()) {
                body.drive(box.get(), yaw);
            }
            Optional<PartState> state = VineParts.part(ref, body.partName());
            body.broken(state.isPresent() && state.get().broken());
        }
    }

    /**
     * The native bodies for this actor's declared parts, built from the type registered
     * beside the entity types (sub-08 Stage D). Created on the first server tick that
     * reaches here and positioned on the actor, so a part whose clip has not started yet
     * sits at its owner instead of at the world origin a fresh entity defaults to.
     */
    private List<VinePartEntity> createPartBodies() {
        EntityType<VinePartEntity> type = FabricContentMaterializer.partEntityType();
        if (type == null) {
            throw new IllegalStateException("the part body type was never materialized — its registration runs"
                + " with the entity types at mod init");
        }
        List<PartDescriptor> parts = this.vineDescriptor.parts();
        List<VinePartEntity> bodies = new ArrayList<>(parts.size());
        for (PartDescriptor part : parts) {
            VinePartEntity body = type.create(getWorld());
            if (body == null) {
                throw new IllegalStateException("the part body type refused to create a body for part '"
                    + part.name() + "' of " + this.vineId);
            }
            body.attachTo(this, part.name());
            bodies.add(body);
        }
        return List.copyOf(bodies);
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
     * Drops whatever brain is attached to this actor, plus its part bodies; a no-op for an
     * untagged body and idempotent, so the removal and unload hooks may both call it.
     */
    void vineDetach() {
        if (this.vineInstance != null) {
            EntityRuntime.detach(actorRef());
            LIVE.remove(actorRef());
        }
        discardPartBodies();
    }

    /**
     * Drops this actor's part bodies (sub-08 Stage D): a body is a carrier for a live
     * actor only, and one left behind would keep a gone actor's geometry. Called from
     * {@link #vineDetach()}, the same place the brain and the part hosting are dropped,
     * so removal and chunk unload both cover it.
     */
    private void discardPartBodies() {
        List<VinePartEntity> bodies = this.partBodies;
        if (bodies == null) {
            return;
        }
        this.partBodies = null;
        for (VinePartEntity body : bodies) {
            body.discard();
        }
    }

    /** This actor's engine identity, built lazily once — the instance id is fixed at spawn. */
    VineEntityRef actorRef() {
        VineEntityRef ref = this.actorRef;
        if (ref == null) {
            this.actorRef = ref = new VineEntityRef(this.vineId, this.vineInstance);
        }
        return ref;
    }

    /** The part host for this actor, built lazily once (sub-08 Stage D). */
    private FabricPartHost partHost() {
        FabricPartHost host = this.partHost;
        if (host == null) {
            this.partHost = host = new FabricPartHost(this);
            // Announced here, not at spawn: only an actor with parts is ever resolved by
            // the delta transport, and a body the world refused at spawn never ticks — so
            // it can never reach this table and the transport's lookup stays honest.
            LIVE.put(actorRef(), this);
        }
        return host;
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
