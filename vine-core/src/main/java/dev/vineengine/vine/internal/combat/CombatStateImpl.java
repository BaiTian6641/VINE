package dev.vineengine.vine.internal.combat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.vineengine.vine.combat.CombatState;
import dev.vineengine.vine.combat.CombatActorRef;

/**
 * The engine's own i-frame and hitstop counters (sub-10 Stage C).
 *
 * <p>Deliberately not vanilla's invulnerability timer: an opted-in creature's i-frames are
 * a fight rule (a dodge window, a part's recoil), and vanilla's timer is bookkeeping about
 * hurt events. Keeping them separate is what lets a scenario say "the second hit was
 * refused by i-frames" and mean the engine's own counter.
 *
 * <p>Counters are tick-driven: the cell calls {@link #tick()} once per server tick, and an
 * actor that has left the world is forgotten by {@link #forget}. A grant replaces rather
 * than adds, taking the longer of the two, so two overlapping grants cannot sum into a
 * window nobody authored.
 */
public final class CombatStateImpl implements CombatState {

    private final Map<CombatActorRef, Integer> iFrames = new ConcurrentHashMap<>();
    private final Map<CombatActorRef, Integer> hitstop = new ConcurrentHashMap<>();

    @Override
    public boolean inIFrames(CombatActorRef target) {
        Integer remaining = target == null ? null : iFrames.get(target);
        return remaining != null && remaining > 0;
    }

    @Override
    public void grantIFrames(CombatActorRef target, int ticks) {
        if (target == null || ticks <= 0) {
            return;
        }
        iFrames.merge(target, ticks, Math::max);
    }

    @Override
    public void applyHitstop(CombatActorRef target, int ticks) {
        if (target == null || ticks <= 0) {
            return;
        }
        hitstop.merge(target, ticks, Math::max);
    }

    @Override
    public int hitstopTicks(CombatActorRef target) {
        Integer remaining = target == null ? null : hitstop.get(target);
        return remaining == null ? 0 : remaining;
    }

    @Override
    public void tick() {
        decay(iFrames);
        decay(hitstop);
    }

    /** Drops every counter for {@code ref} — an actor that is gone keeps no window open. */
    public void forget(CombatActorRef ref) {
        if (ref != null) {
            iFrames.remove(ref);
            hitstop.remove(ref);
        }
    }

    /** How many actors currently have a counter — a probe for tests and the dashboard. */
    public int trackedCount() {
        return iFrames.size() + hitstop.size();
    }

    /**
     * Counts every window down by one tick and drops the expired ones.
     *
     * <p>Deliberately not {@code entrySet().removeIf(... entry.setValue(...))}: an entry of a
     * {@link Map} obtained that way may be an immutable snapshot
     * ({@code AbstractMap.SimpleImmutableEntry}) — as {@link java.util.concurrent.ConcurrentHashMap}
     * produces — and {@code setValue} on it throws {@code UnsupportedOperationException}, once per
     * map entry, on the server tick. That is the crash this method exists to stop having.
     */
    private static void decay(Map<CombatActorRef, Integer> counters) {
        counters.replaceAll((ref, remaining) -> remaining - 1);
        counters.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }
}
