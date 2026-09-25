package dev.vineengine.vine.internal.driver1211.fabric.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.entity.Entity;
import net.minecraft.entity.ai.pathing.EntityNavigation;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import dev.vineengine.vine.brain.Primitives;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 Fabric cell's {@link Primitives} for one live actor (sub-08 Stage C):
 * how a behaviour reaches the world through <em>this cell's</em> machinery, never
 * through a native goal. One instance per {@link VineEntity}, built once by the
 * entity's actor path and reused every tick, so answering a brain allocates only
 * the engine values the interface itself returns.
 *
 * <p><b>Serving, not deciding.</b> Nothing here selects behaviour, installs a goal
 * or maps a {@code VineBrain} onto vanilla AI (§5.13): each call answers a
 * question the engine asked, or advances motion the engine requested, over the
 * actor's own {@link EntityNavigation}, move/look controls and a world raycast.
 * The vanilla AI tick still runs ({@code MobEntity#tickNewAi} ticks navigation and
 * controls), which is what lets a manually started path actually move the body.
 *
 * <p><b>Honest pathing.</b> Fabric's navigation computes synchronously, so this
 * cell never has a path in flight and never reports {@link PathOutcome#PENDING} —
 * a request answers {@code FOUND} or {@code UNREACHABLE} the tick it is made. A
 * request for the target already being followed is answered {@code FOUND} without
 * re-pathfinding, so a brain that asks every tick does not restart its own path.
 * The node a request names is expressed in the body's own coordinates (see
 * {@link #nodeOffset()}): vanilla drives the body centre to a node <em>plus</em>
 * the actor's width offset, so passing the engine's target straight through would
 * leave any body wider than one block short of the point its brain asked for.
 *
 * <p><b>Determinism.</b> Target queries are sorted by engine id (then instance)
 * rather than world order, so two identical worlds yield identical lists; the
 * "single" target operations (line of sight, look) resolve the nearest actor of
 * that kind with the same order as tie-breaker.
 *
 * <p><b>Invariants:</b> engine ids and vectors only cross this class — no native
 * type escapes into {@code vine-api}; the actor is the entity this instance was
 * built for and never changes; every ordinary world condition (no target, no path)
 * is an answer, not a throw.
 */
final class FabricEntityPrimitives implements Primitives {

    /**
     * The navigation speed multiplier for every request: vanilla's full-attribute
     * speed, so the actor's own {@code minecraft:generic.movement_speed} decides how
     * fast it walks. The cell never invents a speed the descriptor did not declare.
     */
    private static final double NAVIGATION_SPEED = 1.0D;

    /**
     * How close counts as arrived at the requested target. Fabric's navigation
     * declares a finished path at its final node, and the node's centre can sit up
     * to about a block from the exact requested point; a slightly larger radius
     * keeps {@link #stepPath()} from reporting {@code BLOCKED} on the tick a short
     * final leg left the body a hair short.
     */
    private static final double ARRIVAL_DISTANCE = 1.5D;

    /**
     * The scan window for the operations whose engine signature carries no radius
     * ({@link #hasLineOfSight(VineId)}, {@link #lookAt(VineId)}). Wide enough to
     * cover any vanilla follow range, and bounded so a probe never scans an
     * unbounded world region.
     */
    private static final double TARGET_SCAN_RADIUS = 64.0D;

    /**
     * Deterministic actor order — the engine id, then the instance id. The same
     * order {@code queryTargets} reports in, and the tie-breaker a "nearest actor"
     * lookup uses, so nothing here depends on the world's iteration order.
     */
    private static final Comparator<VineEntity> ACTOR_ORDER = Comparator
        .comparing(VineEntity::vineId)
        .thenComparing(VineEntity::vineInstance, Comparator.nullsFirst(Comparator.naturalOrder()));

    private final VineEntity actor;

    /** The target of the last successful request, or {@code null} when no path is in flight. */
    private Vec3 pathTarget;

    /** Builds the primitives serving {@code actor}; called once per actor by {@link VineEntity}. */
    FabricEntityPrimitives(VineEntity actor) {
        this.actor = actor;
    }

    @Override
    public Vec3 position() {
        // Read live from the body rather than remembered: a behaviour's idea of
        // "here" must not drift from where the world actually put the actor.
        return Vec3.of(this.actor.getX(), this.actor.getY(), this.actor.getZ());
    }

    @Override
    public PathOutcome requestPath(Vec3 target) {
        EntityNavigation navigation = this.actor.getNavigation();
        Vec3 inFlight = this.pathTarget;
        if (inFlight != null && inFlight.equals(target) && navigation.isFollowingPath()) {
            // The same request a brain makes every tick: keep the path already being
            // walked instead of restarting it from the current node each tick.
            return PathOutcome.FOUND;
        }
        this.pathTarget = null;
        // The native navigation drives the body's centre to the node *plus* an
        // offset derived from the actor's width (vanilla's Path#getNodePosition
        // treats a node as the actor's bounding-box corner, not its centre), so a
        // request in the engine's own coordinate space has to name the node the
        // body must end at. Without this, a wide actor stops short of the requested
        // point by exactly that offset — the honest reason a 2.4-wide body would
        // otherwise never reach a target its own pathfinder can route to.
        double offset = nodeOffset();
        double nodeX = Math.floor(target.x() - offset);
        double nodeZ = Math.floor(target.z() - offset);
        if (!navigation.startMovingTo(nodeX, target.y(), nodeZ, NAVIGATION_SPEED)) {
            // The native request answered synchronously and found no route: the
            // truthful engine answer, not a value this cell invented.
            return PathOutcome.UNREACHABLE;
        }
        this.pathTarget = target;
        return PathOutcome.FOUND;
    }

    @Override
    public StepOutcome stepPath() {
        EntityNavigation navigation = this.actor.getNavigation();
        if (navigation.isFollowingPath()) {
            // The vanilla tick advances the body; the brain only needs to know that
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
     * The distance between a path node and the body centre the navigation aims at,
     * in the cell's own node convention: {@code (int)(width + 1) * 0.5}, the offset
     * vanilla's {@code Path#getNodePosition} applies to every node it hands the
     * movement control. One half block for a body narrower than one block (the
     * ordinary mob), larger for a body with a declared footprint — which is why
     * {@link #requestPath} subtracts it before naming a node.
     */
    private double nodeOffset() {
        return (double) ((int) (this.actor.getWidth() + 1.0F)) * 0.5D;
    }

    @Override
    public boolean hasLineOfSight(VineId target) {
        VineEntity other = nearestActor(target);
        if (other == null) {
            // A kind with no live actor is simply not visible (the interface's
            // answer for an ordinary world condition, never an exception).
            return false;
        }
        Vec3d eyes = this.actor.getEyePos();
        Vec3d targetEyes = other.getEyePos();
        BlockHitResult hit = this.actor.getWorld().raycast(new RaycastContext(
            eyes, targetEyes, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, this.actor));
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
            // The native look control turns the head/body over the following ticks;
            // a gone target is a no-op, exactly the interface's contract.
            this.actor.getLookControl().lookAt(other);
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
            double distance = this.actor.squaredDistanceTo(other);
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
     * {@link #ACTOR_ORDER} — the deterministic scan behind both target queries.
     */
    private List<VineEntity> nearbyActors(double radius) {
        Box box = this.actor.getBoundingBox().expand(radius, radius, radius);
        List<Entity> found = this.actor.getWorld()
            .getOtherEntities(this.actor, box, entity -> entity instanceof VineEntity);
        List<VineEntity> actors = new ArrayList<>(found.size());
        double radiusSquared = radius * radius;
        for (Entity entity : found) {
            VineEntity other = (VineEntity) entity;
            if (this.actor.squaredDistanceTo(other) <= radiusSquared) {
                actors.add(other);
            }
        }
        actors.sort(ACTOR_ORDER);
        return actors;
    }
}
