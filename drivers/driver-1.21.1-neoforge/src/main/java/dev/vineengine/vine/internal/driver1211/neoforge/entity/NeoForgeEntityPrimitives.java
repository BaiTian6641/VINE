package dev.vineengine.vine.internal.driver1211.neoforge.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import dev.vineengine.vine.brain.Primitives;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 NeoForge cell's {@link Primitives} for one live actor (sub-08 Stage C):
 * how a behaviour reaches the world through <em>this cell's</em> machinery, never
 * through a native goal. One instance per {@link VineEntity}, built once by the
 * entity's actor path and reused every tick, so answering a brain allocates only the
 * engine values the interface itself returns.
 *
 * <p><b>Serving, not deciding.</b> Nothing here selects behaviour, installs a goal or
 * maps a {@code VineBrain} onto vanilla AI (§5.13): each call answers a question the
 * engine asked, or advances motion the engine requested, over the actor's own
 * {@link PathNavigation}, move/look controls and a world raycast. The vanilla AI tick
 * still runs ({@code Mob#serverAiStep} ticks navigation and the move/look controls —
 * it is {@code final} and unconditional), which is what lets a manually started path
 * actually move the body.
 *
 * <p><b>Honest pathing.</b> This cell's ground navigation computes synchronously
 * ({@code PathNavigation#moveTo} runs {@code PathFinder} on the calling tick), so a
 * request answers {@code FOUND} or {@code UNREACHABLE} the tick it is made and this
 * class never reports {@link PathOutcome#PENDING}. A request for the target already
 * being followed is answered {@code FOUND} without re-pathfinding, so a brain that
 * asks every tick does not restart its own path.
 *
 * <p><b>The node offset is the trap.</b> Vanilla's {@code Path#getEntityPosAtNode}
 * drives the body <em>centre</em> to a node plus {@code (int)(width + 1) * 0.5} — the
 * node names the corner of the actor's bounding box, not its centre — so passing the
 * engine's target straight through would leave any body wider than one block short of
 * the point its brain asked for (1.5 blocks for the 2.4-wide testbeast), and a body
 * that never gets there is honestly {@code UNREACHABLE} at the platform edge forever.
 * {@link #requestPath} therefore names the node the body must end at, for any width.
 *
 * <p><b>Determinism.</b> Target queries are sorted by engine id (then instance) rather
 * than world order, so two identical worlds yield identical lists; the "single" target
 * operations (line of sight, look) resolve the nearest actor of that kind with the same
 * order as tie-breaker.
 *
 * <p><b>Invariants:</b> engine ids and vectors only cross this class — no native type
 * escapes into {@code vine-api}; the actor is the entity this instance was built for
 * and never changes; every ordinary world condition (no target, no path) is an answer,
 * not a throw.
 */
final class NeoForgeEntityPrimitives implements Primitives {

    /**
     * The navigation speed multiplier for every request: vanilla's full-attribute
     * speed, so the actor's own {@code minecraft:generic.movement_speed} decides how
     * fast it walks. The cell never invents a speed the descriptor did not declare.
     */
    private static final double NAVIGATION_SPEED = 1.0D;

    /**
     * How close counts as arrived at the requested target. The native navigation
     * declares a finished path at its final node, and the node's centre can sit up to
     * about a block from the exact requested point; a slightly larger radius keeps
     * {@link #stepPath()} from reporting {@code BLOCKED} on the tick a short final leg
     * left the body a hair short.
     */
    private static final double ARRIVAL_DISTANCE = 1.5D;

    /**
     * The scan window for the operations whose engine signature carries no radius
     * ({@link #hasLineOfSight(VineId)}, {@link #lookAt(VineId)}). Wide enough to cover
     * any vanilla follow range, and bounded so a probe never scans an unbounded world
     * region.
     */
    private static final double TARGET_SCAN_RADIUS = 64.0D;

    /**
     * Deterministic actor order — the engine id, then the instance id. The same order
     * {@code queryTargets} reports in, and the tie-breaker a "nearest actor" lookup
     * uses, so nothing here depends on the level's iteration order.
     */
    private static final Comparator<VineEntity> ACTOR_ORDER = Comparator
        .comparing(VineEntity::vineId)
        .thenComparing(VineEntity::vineInstance, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final VineEntity actor;

    /** The target of the last successful request, or {@code null} when no path is in flight. */
    private Vec3 pathTarget;

    /** Builds the primitives serving {@code actor}; called once per actor by {@link VineEntity}. */
    NeoForgeEntityPrimitives(VineEntity actor) {
        this.actor = actor;
    }

    @Override
    public Vec3 position() {
        // Read live from the body rather than remembered: a behaviour's idea of "here"
        // must not drift from where the world actually put the actor.
        return Vec3.of(this.actor.getX(), this.actor.getY(), this.actor.getZ());
    }

    @Override
    public PathOutcome requestPath(Vec3 target) {
        PathNavigation navigation = this.actor.getNavigation();
        Vec3 inFlight = this.pathTarget;
        if (inFlight != null && inFlight.equals(target) && navigation.isInProgress()) {
            // The same request a brain makes every tick: keep the path already being
            // walked instead of restarting it from the current node each tick.
            return PathOutcome.FOUND;
        }
        this.pathTarget = null;
        // Name the node the body centre must end at, not the engine point itself, for
        // the reason this class' javadoc records — vanilla adds the width-derived
        // offset on top of whatever node it is given.
        double offset = nodeOffset();
        double nodeX = Math.floor(target.x() - offset);
        double nodeZ = Math.floor(target.z() - offset);
        if (!navigation.moveTo(nodeX, target.y(), nodeZ, NAVIGATION_SPEED)) {
            // The native request answered on this tick and found no route (or the body
            // cannot path right now, e.g. not yet on the ground): the truthful engine
            // answer, not a value this cell invented.
            return PathOutcome.UNREACHABLE;
        }
        this.pathTarget = target;
        return PathOutcome.FOUND;
    }

    @Override
    public StepOutcome stepPath() {
        PathNavigation navigation = this.actor.getNavigation();
        if (navigation.isInProgress()) {
            // The vanilla AI tick advances the body; the brain only needs to know that
            // the path it asked for is still being walked.
            return StepOutcome.ADVANCED;
        }
        if (this.pathTarget != null && arrived(this.pathTarget)) {
            return StepOutcome.ARRIVED;
        }
        // No path in flight and not at the target: nothing this cell can advance.
        return StepOutcome.BLOCKED;
    }

    /**
     * The distance between a path node and the body centre the navigation aims at, in
     * the cell's own node convention: {@code (int)(width + 1) * 0.5}, the offset
     * vanilla's {@code Path#getEntityPosAtNode} (and the matching waypoint check in
     * {@code PathNavigation#followThePath}) applies to every node it hands the move
     * control. One half block for a body narrower than one block (the ordinary mob),
     * larger for a body with a declared footprint — which is why {@link #requestPath}
     * subtracts it before naming a node.
     */
    private double nodeOffset() {
        return (double) ((int) (this.actor.getBbWidth() + 1.0F)) * 0.5D;
    }

    @Override
    public boolean hasLineOfSight(VineId target) {
        VineEntity other = nearestActor(target);
        if (other == null) {
            // A kind with no live actor is simply not visible (the interface's answer
            // for an ordinary world condition, never an exception).
            return false;
        }
        net.minecraft.world.phys.Vec3 eyes = this.actor.getEyePosition();
        net.minecraft.world.phys.Vec3 targetEyes = other.getEyePosition();
        BlockHitResult hit = this.actor.level().clip(new ClipContext(
            eyes, targetEyes, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this.actor));
        return hit.getType() == HitResult.Type.MISS;
    }

    @Override
    public List<VineId> queryTargets(double radius) {
        List<VineEntity> actors = nearbyActors(radius);
        List<VineId> ids = new ArrayList<>(actors.size());
        for (VineEntity other : actors) {
            ids.add(other.vineId());
        }
        return List.copyOf(ids);
    }

    @Override
    public void lookAt(VineId target) {
        VineEntity other = nearestActor(target);
        if (other != null) {
            // The native look control turns the head/body over the following ticks; a
            // gone target is a no-op, exactly the interface's contract.
            this.actor.getLookControl().setLookAt(other);
        }
    }

    /** Whether the body is within {@link #ARRIVAL_DISTANCE} of {@code target}. */
    private boolean arrived(Vec3 target) {
        double dx = this.actor.getX() - target.x();
        double dy = this.actor.getY() - target.y();
        double dz = this.actor.getZ() - target.z();
        return dx * dx + dy * dy + dz * dz <= ARRIVAL_DISTANCE * ARRIVAL_DISTANCE;
    }

    /** The nearest live actor of {@code target}'s kind within the scan window, or null. */
    private VineEntity nearestActor(VineId target) {
        VineEntity nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (VineEntity other : nearbyActors(TARGET_SCAN_RADIUS)) {
            if (!other.vineId().equals(target)) {
                continue;
            }
            double distance = this.actor.distanceToSqr(other);
            if (distance < nearestDistance) {
                // Strict < keeps the first in deterministic order on a tie.
                nearestDistance = distance;
                nearest = other;
            }
        }
        return nearest;
    }

    /**
     * Every other engine actor within {@code radius} blocks of this one, in
     * {@link #ACTOR_ORDER} — the deterministic scan behind both target queries. The
     * class filter is the engine-entity filter (only {@link VineEntity} carries an
     * engine id), and the actor itself is excluded so it never answers as its own
     * neighbour.
     */
    private List<VineEntity> nearbyActors(double radius) {
        AABB box = this.actor.getBoundingBox().inflate(radius, radius, radius);
        List<VineEntity> found = this.actor.level()
            .getEntitiesOfClass(VineEntity.class, box, other -> other != this.actor);
        List<VineEntity> actors = new ArrayList<>(found.size());
        double radiusSquared = radius * radius;
        for (VineEntity other : found) {
            if (this.actor.distanceToSqr(other) <= radiusSquared) {
                actors.add(other);
            }
        }
        actors.sort(ACTOR_ORDER);
        return actors;
    }
}
