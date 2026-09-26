package dev.vineengine.vine.internal.driver1211.neoforge.entity;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.entity.PartEntity;

import dev.vineengine.vine.entity.AttributeSpec;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.internal.driver1211.neoforge.net.NeoForgePartTransport;
import dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeContentMaterializer;
import dev.vineengine.vine.internal.entity.EntityRuntime;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 NeoForge cell's native entity carrier (sub-08 Stage A): one vanilla
 * {@link PathfinderMob} per materialized {@code vine:entity} descriptor, carrying
 * the engine id and descriptor it was created from. The engine never sees this
 * class — {@code EntityDriver.spawn} creates it and reports the engine values
 * back — so a native type stays inside the driver jar.
 *
 * <p><b>No native AI.</b> {@link #registerGoals} installs nothing: behaviour is
 * engine-owned from the start (Stage C wires the engine's own scheduler), and
 * mapping {@code VineBrain} onto vanilla goals is forbidden (§5.13). A mobile but
 * goal-less mob is this stage's correct shape, not a gap.
 *
 * <p><b>Driving the engine's loop (sub-08 Stage C).</b> {@link #tick()} hands this
 * body to {@link EntityRuntime#tickActor} once per server tick, with a per-actor
 * {@link NeoForgeEntityPrimitives} built once and cached, so an unattached entity
 * pays one identity check per tick and nothing else. The engine owns the decision
 * (whether a brain is attached, and what it does); this class owns the body
 * (position, navigation, sight). A removed or unloaded entity detaches itself —
 * {@link #remove} covers the level's explicit removals and the driver's
 * {@code EntityLeaveLevelEvent} listener covers chunk unload, which vanilla routes
 * through the final {@code setRemoved} and cannot be overridden — so a brain never
 * outlives the body it was driving.
 *
 * <p><b>Parts (sub-08 Stage D).</b> A descriptor that declares parts makes this body a
 * multipart entity in the loader's own sense: one native {@link PartEntityBody} per declared
 * part, created here (server-side only), advertised through {@link #isMultipartEntity()} and
 * {@link #getParts()}, and placed every server tick at the centre of the box the engine
 * computed — this class never derives a part's geometry itself. The engine learns where the
 * body stands and which way it faces from {@link NeoForgePartHost}, and keeps the part state
 * in the body's own stored tree, so the cell owns where the parts are and the engine owns
 * what they mean. A descriptor with no parts gets no bodies and pays one list test per tick.
 *
 * <p><b>Attributes.</b> Base values ride the type's {@code AttributeSupplier},
 * registered with the type by
 * {@link NeoForgeContentMaterializer#registerEntities} through NeoForge's
 * {@code EntityAttributeCreationEvent} (the moment attribute containers exist —
 * later than type registration, and after every {@code RegisterEvent} firing);
 * the descriptor's modifiers are installed here, on the instance, because vanilla's
 * container builder has no modifier slots. A modifier an attribute instance cannot
 * carry (no instance for a declared attribute) fails loudly rather than being
 * skipped.
 *
 * <p><b>Invariants:</b> the id is the descriptor's id — the materializer checks the
 * holder agrees before either reaches here; the descriptor is the exact immutable
 * one the type was materialized for. Neither field changes for the entity's
 * lifetime.
 */
public final class VineEntity extends PathfinderMob {

    /** No parts declared (or a client-side copy): one shared empty array, never mutated. */
    private static final PartEntityBody[] NO_PARTS = new PartEntityBody[0];

    private final VineId vineId;
    private final EntityDescriptor vineDescriptor;

    /**
     * The native bodies carrying this entity's parts, one per declared part in declaration
     * order, or {@link #NO_PARTS} when the descriptor declares none.
     */
    private final PartEntityBody[] partBodies;

    /**
     * Creates the carrier for {@code descriptor}. Called only from the type's
     * factory ({@link NeoForgeContentMaterializer#registerEntities}), which is the
     * one place both arguments are in scope.
     */
    public VineEntity(EntityType<? extends VineEntity> type, Level level, VineId id, EntityDescriptor descriptor) {
        super(type, level);
        this.vineId = id;
        this.vineDescriptor = descriptor;
        installModifiers(descriptor);
        this.partBodies = createPartBodies(level, descriptor);
        if (this.partBodies.length > 0) {
            // Vanilla's own multipart id rule (MC-158205): a part's id has to be a
            // successor of its parent's, so the block is reserved here and handed out by
            // the setId below — which the level may call again when it assigns ids.
            this.setId(ENTITY_COUNTER.getAndAdd(this.partBodies.length + 1) + 1);
        }
    }

    /**
     * Creates one body per declared part, server-side only. A client's copy of a spawned
     * entity has no engine actor (the spawn path is what tags an instance), so a client-side
     * body would be a collider nobody ever places; the client's parts are the engine's own
     * business, not this cell's.
     */
    private PartEntityBody[] createPartBodies(Level level, EntityDescriptor descriptor) {
        if (level.isClientSide() || descriptor.parts().isEmpty()) {
            return NO_PARTS;
        }
        PartEntityBody[] bodies = new PartEntityBody[descriptor.parts().size()];
        for (int i = 0; i < bodies.length; i++) {
            bodies[i] = new PartEntityBody(this, descriptor.parts().get(i));
        }
        return bodies;
    }

    @Override
    protected void registerGoals() {
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
     * order. Permanent, so a later equipment or effect change cannot silently
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
            Holder<Attribute> attribute = NeoForgeContentMaterializer.attributeHolder(spec.attribute());
            AttributeInstance instance = this.getAttribute(attribute);
            if (instance == null) {
                throw new IllegalStateException("entity " + descriptor.id() + ": attribute " + spec.attribute()
                    + " has no instance on a fresh entity — its default container was not registered with it");
            }
            for (dev.vineengine.vine.entity.AttributeModifier modifier : spec.modifiers()) {
                instance.addOrReplacePermanentModifier(new AttributeModifier(
                    ResourceLocation.fromNamespaceAndPath(modifier.id().namespace(), modifier.id().path()),
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
    private static AttributeModifier.Operation operation(
            dev.vineengine.vine.entity.AttributeModifier.Operation operation) {
        return switch (operation) {
            case ADD -> AttributeModifier.Operation.ADD_VALUE;
            case MULTIPLY_BASE -> AttributeModifier.Operation.ADD_MULTIPLIED_BASE;
            case MULTIPLY_TOTAL -> AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL;
        };
    }

    /** The engine's per-instance identity (sub-08 Stage C); set by the spawn path. */
    private java.util.UUID vineInstance;

    /** Tags this entity with its engine instance id — called only by the cell's spawn path. */
    public void vineInstance(java.util.UUID instance) {
        this.vineInstance = java.util.Objects.requireNonNull(instance, "instance");
    }

    /** The engine's instance id for this entity, or {@code null} before the spawn path sets it. */
    public java.util.UUID vineInstance() {
        return vineInstance;
    }

    /** This actor's engine identity, built once on the first tick and reused (no per-tick allocation). */
    private VineEntityRef actorRef;

    /** The per-actor world primitives the engine's loop is driven with; built alongside {@link #actorRef}. */
    private NeoForgeEntityPrimitives actorPrimitives;

    /** The per-actor part host, built once and handed to the engine every server tick. */
    private NeoForgePartHost partHost;

    /**
     * Drives the engine's actor for this body (sub-08 Stage C): one callback per server
     * tick, handing the engine this actor's primitives. Nothing is attached for a plain
     * entity, and {@link EntityRuntime#tickActor} answers that case without work, so
     * Stage A's behaviour is unchanged — the only cost is the instance-id check below.
     *
     * <p>Parts (sub-08 Stage D) ride the same tick, and only for a descriptor that declares
     * them: the engine is handed this body's host before anything can ask it about a part,
     * then — if a clip is hosting them — the native bodies are placed at the centres the
     * engine computed. A descriptor with no parts pays one empty-list test.
     */
    @Override
    public void tick() {
        super.tick();
        if (this.vineInstance == null) {
            // Never tagged by the engine's spawn path (or a client-side copy of a spawned
            // entity): there is no actor to drive — and no engine box to place parts at, so
            // a body that does exist (a server-side copy reloaded from the save) stays on
            // its parent rather than at the origin it was built at.
            keepPartsWithParent();
            return;
        }
        VineEntityRef ref = actorRef();
        EntityRuntime.tickActor(ref, actorPrimitives());
        if (!this.vineDescriptor.parts().isEmpty()) {
            // The host is (re)bound every tick, exactly as the actor's primitives are:
            // the engine reads the body's live position and yaw from it, and hosting a
            // clip needs a host that is already bound.
            EntityRuntime.tickParts(ref, partHost());
            drivePartBodies(ref);
        }
    }

    /**
     * Places every native part body at the centre of the box the engine computed for it this
     * tick. The cell derives no geometry: the box is the engine's, and the body is only its
     * axis-aligned proxy in the world.
     */
    private void drivePartBodies(VineEntityRef ref) {
        if (this.partBodies.length == 0) {
            // Client-side copy (parts are created server-side only): nothing to place.
            return;
        }
        if (!VineParts.isHosted(ref)) {
            // No clip is hosting the parts, so the engine has no pose and no box to hand
            // back; the bodies then stay on their parent, which is where an unanimated part
            // honestly is.
            keepPartsWithParent();
            return;
        }
        Vec3 position = Vec3.of(this.getX(), this.getY(), this.getZ());
        float yaw = this.getYRot();
        for (PartEntityBody body : this.partBodies) {
            // The box is looked up by the part's engine name, never by an index: the name is
            // what the part's state and its geometry share.
            VineParts.box(ref, body.partName(), position, yaw)
                .ifPresent(box -> body.placeAt(box.center()));
        }
    }

    /** Places every body at this entity's own position; a no-op for an entity with no parts. */
    private void keepPartsWithParent() {
        if (this.partBodies.length == 0) {
            return;
        }
        Vec3 position = Vec3.of(this.getX(), this.getY(), this.getZ());
        for (PartEntityBody body : this.partBodies) {
            body.placeAt(position);
        }
    }

    /**
     * Whether this body is a multipart entity in the loader's sense: true exactly when it
     * carries native part bodies, which is the server-side copy of a descriptor that
     * declares parts. The level reads this once, when the body starts being tracked, to put
     * the parts into its part table — the same route vanilla's dragon uses.
     */
    @Override
    public boolean isMultipartEntity() {
        return this.partBodies.length > 0;
    }

    /** The native bodies carrying the parts, in declaration order (never null, never rebuilt). */
    @Override
    public PartEntity<?>[] getParts() {
        return this.partBodies;
    }

    /**
     * Keeps every part's id a successor of this body's, which is what vanilla's own
     * multipart fix (MC-158205) requires; the level may re-assign this body's id and the
     * parts have to follow it.
     */
    @Override
    public void setId(int id) {
        super.setId(id);
        for (int i = 0; i < this.partBodies.length; i++) {
            this.partBodies[i].setId(id + i + 1);
        }
    }

    /**
     * Detaches this body's brain when the level removes it, so a removed actor never
     * keeps being ticked by a brain it no longer has a body for. Chunk unload does not
     * pass through here (vanilla's {@code setRemoved} is final); the driver's
     * {@code EntityLeaveLevelEvent} listener covers that path with the same
     * {@link #vineDetach()} call.
     */
    @Override
    public void remove(RemovalReason reason) {
        super.remove(reason);
        vineDetach();
    }

    /**
     * Drops everything the engine holds for this body: its brain, its part hosting (through
     * {@link EntityRuntime#detach}), and the native part bodies themselves — a part must never
     * outlive the body it belongs to, before or after a chunk unload. A no-op for an untagged
     * body and idempotent, so the removal and unload hooks may both call it. The driver's
     * unload listener calls it, which is why it is package-private rather than private.
     */
    void vineDetach() {
        if (this.vineInstance != null) {
            // Detach first: it mints the ref when this body never ticked, and a removed body
            // has to end up unregistered from the part-delta transport, not registered by it.
            EntityRuntime.detach(actorRef());
        }
        discardPartBodies();
    }

    /**
     * Discards the native part bodies and forgets this body as their actor's live carrier. A
     * discarded part is what the loader's own part machinery understands as gone; the level
     * also drops the parts from its part table on the same leave-level path.
     */
    private void discardPartBodies() {
        if (this.partBodies.length == 0) {
            return;
        }
        NeoForgePartTransport.unbind(this);
        for (PartEntityBody body : this.partBodies) {
            body.discard();
        }
    }

    /**
     * This actor's engine identity, built lazily once — the instance id is fixed at spawn.
     * Minting it is also the moment the actor's identity exists for the part-delta transport,
     * which routes a delta by the body carrying it. Package-private because the actor's
     * primitives answer engine questions about the same actor.
     */
    VineEntityRef actorRef() {
        VineEntityRef ref = this.actorRef;
        if (ref == null) {
            this.actorRef = ref = new VineEntityRef(this.vineId, this.vineInstance);
            // The actor's identity is what a part delta is routed by, so the transport is
            // told about this body exactly when that identity is minted; a descriptor that
            // declares no parts is not remembered at all.
            NeoForgePartTransport.bind(this);
        }
        return ref;
    }

    /**
     * This body's engine identity, or {@code null} when no spawn path tagged it (a client's
     * copy of a spawned entity, or a body the engine never spawned). The identity is the pair
     * the engine assigned at spawn, so a cell that needs to name the actor a native entity is
     * — the combat hooks, for one — asks here rather than rebuilding it.
     */
    public VineEntityRef vineActorRef() {
        return this.vineInstance == null ? null : actorRef();
    }

    /**
     * The primitives serving this actor, built lazily once and reused every tick.
     */
    private NeoForgeEntityPrimitives actorPrimitives() {
        NeoForgeEntityPrimitives primitives = this.actorPrimitives;
        if (primitives == null) {
            this.actorPrimitives = primitives = new NeoForgeEntityPrimitives(this);
        }
        return primitives;
    }

    /** The part host serving this actor, built lazily once and reused every tick. */
    private NeoForgePartHost partHost() {
        NeoForgePartHost host = this.partHost;
        if (host == null) {
            this.partHost = host = new NeoForgePartHost(this);
        }
        return host;
    }
}
