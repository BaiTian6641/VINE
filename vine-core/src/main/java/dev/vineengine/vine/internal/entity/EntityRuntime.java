package dev.vineengine.vine.internal.entity;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import dev.vineengine.vine.brain.NodeStatus;
import dev.vineengine.vine.brain.Primitives;
import dev.vineengine.vine.brain.VineBrain;
import dev.vineengine.vine.entity.VineEntityRef;

/**
 * The engine's actor table (sub-08 Stage C): which live instance runs which brain.
 *
 * <p>The engine owns the loop and the cell only drives it — a cell's entity ticks
 * once per server tick and calls {@link #tickActor}, handing in its own
 * {@link Primitives}. That keeps the behaviour scheduler engine-side (a cell never
 * decides what an actor does) while the cell stays responsible for everything the
 * actor physically is: its body, its navigation, what it can see.
 *
 * <p><b>Lifecycle:</b> an actor exists between {@code attach} and {@code detach}. A
 * cell detaches when its entity is removed or unloaded, so a brain never outlives
 * its body and a reloaded world starts with no stale actors. Ticking an actor that
 * has none attached does nothing — a cell does not have to ask first, which keeps
 * the per-tick path allocation-free for unattached entities.
 */
public final class EntityRuntime {

    private static final Map<VineEntityRef, VineBrain> BRAINS = new ConcurrentHashMap<>();

    /**
     * Answers "is this actor frozen right now" for the brain loop (sub-10 hitstop). Installed
     * once by the engine's own pipeline; absent means nothing freezes, which is the correct
     * answer for every engine that has no combat at all.
     */
    @FunctionalInterface
    public interface HitstopSource {
        int hitstopTicks(VineEntityRef ref);
    }

    private static volatile HitstopSource hitstop = ref -> 0;

    private EntityRuntime() {
    }

    /**
     * Attaches {@code brain} to {@code ref}.
     *
     * @throws IllegalStateException if that ref already has a brain
     */
    public static void attach(VineEntityRef ref, VineBrain brain) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(brain, "brain");
        VineBrain previous = BRAINS.putIfAbsent(ref, brain);
        if (previous != null) {
            throw new IllegalStateException("actor " + ref + " already has a brain attached — replacing one would"
                + " discard actions in progress");
        }
    }

    /** Drops whatever is attached to {@code ref}; idempotent. */
    public static void detach(VineEntityRef ref) {
        if (ref != null) {
            BRAINS.remove(ref);
            PartRuntime.detach(ref);
        }
    }

    /**
     * Binds (or refreshes) {@code ref}'s part host and advances its pose clock once.
     * The cell drives its own body as before; this is where the engine learns where
     * that body stands so it can place the parts it owns. An actor with no parts
     * hosted pays one map put and nothing else.
     */
    public static void tickParts(VineEntityRef ref, dev.vineengine.vine.internal.spi.PartHost host) {
        PartRuntime.tick(ref, host);
    }

    /** Whether {@code ref} is hosting parts — for probes and tests. */
    public static boolean hasParts(VineEntityRef ref) {
        return PartRuntime.isHosted(ref);
    }

    /** Stops part hosting for {@code ref} (its stored wound state stays in the tree). */
    public static void detachParts(VineEntityRef ref) {
        PartRuntime.detach(ref);
    }

    /**
     * Ticks {@code ref}'s brain once, if it has one.
     *
     * @return the status the brain reported, or empty when nothing is attached (the
     *         common case for a plain entity, and the reason this is not an error)
     */
    public static java.util.Optional<NodeStatus> tickActor(VineEntityRef ref, Primitives primitives) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(primitives, "primitives");
        VineBrain brain = BRAINS.get(ref);
        if (brain == null) {
            return java.util.Optional.empty();
        }
        if (hitstop.hitstopTicks(ref) > 0) {
            // Frozen: the body is stunned, so the decision loop holds its state without
            // advancing. Not ticking at all (rather than ticking and ignoring) is what keeps
            // a hitstop from being a free action window for a creature that is meant to be
            // reeling.
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(brain.tick(primitives));
    }

    /** Installs the hitstop source (the combat pipeline); {@code null} restores "never frozen". */
    public static void hitstopSource(HitstopSource source) {
        hitstop = source == null ? ref -> 0 : source;
    }

    /** Whether {@code ref} has a brain attached — for probes and tests. */
    public static boolean isAttached(VineEntityRef ref) {
        return ref != null && BRAINS.containsKey(ref);
    }

    /** How many actors currently run a brain — the number a cell's tick loop must match. */
    public static int attachedCount() {
        return BRAINS.size();
    }
}
