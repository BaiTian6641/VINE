package dev.vineengine.vine.internal.driver1211.fabric.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.server.network.EntityTrackerEntry;
import net.minecraft.world.World;

import dev.vineengine.vine.animation.OrientedBox;

/**
 * The 1.21.1 Fabric cell's native body for one declared part (sub-08 Stage D). This
 * loader has no {@code PartEntity} primitive, so the vanilla mechanism is hosted
 * manually: a small {@link Entity} the actor creates from its own registered type,
 * keeps in a list, and places every server tick from the box the engine computed.
 *
 * <p><b>What the vanilla mechanism actually is.</b> Vanilla's only multipart entity is
 * the ender dragon, and its parts ({@code EnderDragonPart}) are <em>never added to the
 * world</em>: they are server-side objects the dragon positions itself, whose
 * bounding boxes the dragon's own damage resolution tests. A part is never saved,
 * never tracked, and never given a spawn packet — the client builds its own view of a
 * dragon model, not of its parts. This class mirrors that shape faithfully, which is
 * also why it is safe: a native entity type with no client renderer registered would
 * NPE this loader's render dispatcher the moment a client started tracking it, and
 * "invent a rendering path" is explicitly not this stage's job. The part's geometry
 * that gameplay acts on lives in the engine's {@link OrientedBox}; this body is the
 * carrier that holds the engine's position on the server.
 *
 * <p><b>Who moves it.</b> Nobody but its actor. {@link #tick()} is deliberately empty
 * — a part that ran so much as gravity would drift away from the box the engine
 * computed, and two answers to "where is the tail" is one too many. The actor calls
 * {@link #drive} with the centre of the engine's box, and that centre is the part's
 * position.
 *
 * <p><b>Invariants</b> — each one is a guard, not a feature, because a part may never
 * become a second gameplay path beside the engine's:
 * <ul>
 *   <li>never saved: {@link #shouldSave()}/{@link #saveSelfNbt} refuse, and the custom
 *       data hooks are no-ops, so no save path can write a part's position. Part
 *       <em>state</em> is stored in the actor's own tree (see {@link FabricPartHost}),
 *       which is what survives a reload;</li>
 *   <li>never damageable: {@link #isInvulnerable()} is true and {@link #damage} always
 *       answers {@code false}, so a vanilla arrow or a player's swing can never wound a
 *       part behind the engine's back ({@code VineParts.applyHit} is the only path);</li>
 *   <li>never a collision or targeting candidate: {@link #canHit()},
 *       {@link #isAttackable()}, {@link #isCollidable()} and {@link #isPushable()} are
 *       false;</li>
 *   <li>never tracked: {@link #createSpawnPacket} throws, exactly as vanilla's part
 *       does — a part belongs on the server, and asking this cell to put one on the
 *       wire is a caller bug, not a state to degrade into.</li>
 * </ul>
 *
 * <p><b>Documented gap (visuals).</b> This cell has no way to show a part — no renderer
 * for the type (see above) and no model swap for a broken one — so the engine's
 * {@code broken} flag is only <em>recorded</em> on the body ({@link #isBroken()}) for
 * a later render stage to read; nothing draws it. That is a gap in this stage, not a
 * silent fallback.
 *
 * <p><b>Prime Invariant:</b> this class is the driver's, and no type of it crosses into
 * {@code vine-api} or {@code vine-core}: the actor hands the engine an engine position
 * and the engine's own box type only.
 */
public final class VinePartEntity extends Entity {

    /** The actor this body carries a part of; set once, by {@link #attachTo}. */
    private VineEntity parent;

    /** The engine's name for the part this body carries; set once, by {@link #attachTo}. */
    private String partName;

    /** Whether the engine currently reports this part broken — mirrored state, never authoritative. */
    private boolean broken;

    /**
     * Creates an unpositioned body; called only by the registered part type's factory
     * (see {@code FabricContentMaterializer#registerPartEntityType}), which is why the
     * parent and part name arrive through {@link #attachTo} instead of the constructor.
     */
    public VinePartEntity(EntityType<? extends VinePartEntity> type, World world) {
        super(type, world);
        // A part is geometry the engine computed, not a physical object: it never
        // collides with blocks and never falls, so only its actor may move it.
        this.noClip = true;
        this.setNoGravity(true);
    }

    /**
     * Ties this body to the part of {@code parent} named {@code name}, placing it on its
     * owner so an un-hosted part is at the actor rather than at the world origin the
     * fresh entity would otherwise sit at. Called once, by the actor that created it.
     */
    void attachTo(VineEntity parent, String name) {
        if (this.parent != null) {
            throw new IllegalStateException("part body '" + this.partName + "' is already attached to "
                + this.parent.vineId() + " — a body carries one part for one actor");
        }
        this.parent = java.util.Objects.requireNonNull(parent, "parent");
        this.partName = java.util.Objects.requireNonNull(name, "name");
        this.refreshPositionAndAngles(parent.getX(), parent.getY(), parent.getZ(), parent.getYaw(), 0.0F);
    }

    /** The actor this body carries a part of, or {@code null} before {@link #attachTo}. */
    public VineEntity parent() {
        return this.parent;
    }

    /** The engine's name for the part this body carries, or {@code null} before {@link #attachTo}. */
    public String partName() {
        return this.partName;
    }

    /**
     * Puts this body where the engine says the part is: the box's <em>centre</em> becomes
     * the position, and the actor's yaw becomes this body's. The box's own rotation
     * cannot be carried by a native entity (this cell has no oriented body), and the
     * actor's yaw is the one component of it the box was composed with — so the yaw a
     * later consumer reads here is the truthful part of the orientation, never a value
     * this cell made up.
     */
    void drive(OrientedBox box, float yawDegrees) {
        // The double overload, not Entity#setPosition(Vec3d): this runs once per part
        // per tick, and the engine's own Vec3 is already the value in hand.
        this.setPosition(box.center().x(), box.center().y(), box.center().z());
        this.setYaw(yawDegrees);
    }

    /**
     * Records the engine's broken flag for this part. This cell has no visual path to
     * carry it (documented gap on the class), so the flag lives here for whichever
     * render stage reads it next — the engine's state stays the only source of truth.
     */
    void broken(boolean broken) {
        this.broken = broken;
    }

    /** Whether the engine reported this part broken at this body's last drive. */
    public boolean isBroken() {
        return this.broken;
    }

    /**
     * Nothing: the actor places this body every tick, and a part must never move on its
     * own (see the class javadoc). The body is also not in the world's entity list, so
     * vanilla does not tick it at all — this override is the invariant, stated where a
     * future change would otherwise be tempted to add motion.
     */
    @Override
    public void tick() {
    }

    /** No tracked data: a part has no client-side state to sync (it is never tracked). */
    @Override
    protected void initDataTracker(DataTracker.Builder builder) {
    }

    /** Nothing to read: a part is never loaded from a save (see {@link #shouldSave()}). */
    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
    }

    /** Nothing to write: a part is never saved (see {@link #shouldSave()}). */
    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
    }

    /** Never saved: a part is not world content, it is its actor's carrier. */
    @Override
    public boolean shouldSave() {
        return false;
    }

    /** Never saved, even when a caller writes the actor's NBT and asks for its parts. */
    @Override
    public boolean saveSelfNbt(NbtCompound nbt) {
        return false;
    }

    /**
     * Throws: a part must never be tracked. Vanilla's own part does the same, and this
     * cell has no renderer for the type — a spawn packet would end in a client crash
     * rather than a part anybody can see.
     */
    @Override
    public Packet<ClientPlayPacketListener> createSpawnPacket(EntityTrackerEntry entry) {
        throw new UnsupportedOperationException("part bodies are server-side only: '"
            + this.partName + "' of " + (this.parent == null ? "an unattached actor" : this.parent.vineId())
            + " must never be sent to a client");
    }

    /** Never a projectile or attack target: parts take damage through the engine only. */
    @Override
    public boolean canHit() {
        return false;
    }

    /** Never a targeting candidate (vanilla's target predicates ask this). */
    @Override
    public boolean isAttackable() {
        return false;
    }

    /** No block collision: the engine's oriented box is the collider. */
    @Override
    public boolean isCollidable() {
        return false;
    }

    /** Never pushed: a part follows its actor and nothing else. */
    @Override
    public boolean isPushable() {
        return false;
    }

    /** Never damageable through vanilla, whatever the damage source claims to bypass. */
    @Override
    public boolean isInvulnerable() {
        return true;
    }

    /**
     * Never damaged through vanilla — not even by a source tagged to bypass invulnerability.
     * {@code VineParts.applyHit} is the one path that wounds a part, so a native hit answers
     * {@code false} rather than opening a parallel one.
     */
    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    /**
     * Vanilla's own part shape: a part reports as part of <em>itself</em> and of its actor,
     * which is what target predicates ask to keep from treating one beast's tail as a second
     * target.
     */
    @Override
    public boolean isPartOf(Entity entity) {
        return entity == this || entity == this.parent;
    }
}
