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
        }
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
        return brain == null ? java.util.Optional.empty() : java.util.Optional.of(brain.tick(primitives));
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
