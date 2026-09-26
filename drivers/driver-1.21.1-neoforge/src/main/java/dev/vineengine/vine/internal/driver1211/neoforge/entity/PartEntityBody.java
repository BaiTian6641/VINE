package dev.vineengine.vine.internal.driver1211.neoforge.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.entity.PartEntity;

import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.world.Vec3;

/**
 * One native body carrying one engine part (sub-08 Stage D): a {@link PartEntity} child of
 * a {@link VineEntity}, named and sized from the engine's {@link PartDescriptor} and
 * placed by its parent at the centre of the box the engine computed.
 *
 * <p><b>Shape follows vanilla's {@code EnderDragonPart}.</b> It knows its parent and its
 * own part name, reports the parent as itself for {@link #is(Entity)} identity checks,
 * carries no AI, gravity or tick of its own, is never saved, and cannot be sent as an
 * entity of its own ({@link PartEntity#getAddEntityPacket} throws — the loader's own
 * contract). A part enters the world the way vanilla's does: the parent advertises
 * {@link VineEntity#isMultipartEntity()} and returns these bodies from
 * {@link VineEntity#getParts()}, which is what puts them into the server level's part
 * table and into the level's entity queries.
 *
 * <p><b>Vanilla damage is dead here, on purpose.</b> Vanilla's dragon part is pickable and
 * forwards a hit to its parent; both halves of that would be wrong in this engine. A
 * forwarded hit is a second damage path the engine never hears about, and pickability
 * exists only to receive one — so a body stays unpickable ({@link Entity#isPickable} is
 * false by default) and is permanently invulnerable besides. A part therefore never takes
 * vanilla damage, and never shields its parent from it either: the engine's own pipeline is
 * the one way a part takes damage, and the body's own damage path is the one it always had.
 *
 * <p><b>What a body is not.</b> It is not the authoritative collider — the engine's
 * oriented box (which may be rotated mid-swing) is what a sweep tests. A body is the
 * axis-aligned proxy the game needs in order to have something to look at and collide
 * with: its centre is the engine box's centre and its extent is the descriptor's declared
 * part size.
 *
 * <p><b>Invariants:</b> the parent never changes; the name is the engine part's name, so a
 * body always matches the state the engine keeps under it; the body never moves itself —
 * the parent places it every server tick, or it does not move at all.
 */
public final class PartEntityBody extends PartEntity<VineEntity> {

    private final String partName;
    private final EntityDimensions size;

    /**
     * Creates the body of {@code part} on {@code parent}; called once per declared part by
     * {@link VineEntity}, server-side only.
     */
    PartEntityBody(VineEntity parent, PartDescriptor part) {
        super(parent);
        this.partName = part.name();
        this.size = EntityDimensions.scalable((float) part.size().x(), (float) part.size().y());
        this.refreshDimensions();
        // The engine owns part damage (see the class javadoc): a vanilla path must never
        // observe a part as damageable.
        this.setInvulnerable(true);
    }

    /** The engine part this body carries — the name its state is stored under. */
    public String partName() {
        return this.partName;
    }

    /**
     * Places this body at {@code center} — the centre of its part's box for the tick.
     *
     * <p>The previous position is remembered first: a part never ticks, so nothing else
     * would ever advance its old-position fields, and the game interpolates a body between
     * them (a body that never updated them would be rendered and hit from the origin).
     */
    public void placeAt(Vec3 center) {
        this.setOldPosAndRot();
        this.setPos(center.x(), center.y(), center.z());
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.size;
    }

    /** A part is a child of its parent, never a world entity: nothing here is saved. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    /** The parent answers for its parts, exactly as vanilla's dragon part does. */
    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        // No synched state: the body is placed by its parent and its gameplay state is the
        // engine's, stored in the actor's tree rather than in a data watcher.
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag compound) {
        // Never saved ({@link #shouldBeSaved} is false).
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag compound) {
        // Never saved ({@link #shouldBeSaved} is false).
    }
}
