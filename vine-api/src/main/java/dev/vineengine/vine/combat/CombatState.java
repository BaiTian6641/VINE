package dev.vineengine.vine.combat;


/**
 * The engine-owned combat state of one actor (sub-10 §2): invulnerability frames and
 * hitstop. Owned by the engine rather than by vanilla's invulnerability timer, because
 * an opted-in creature's i-frames must obey the fight's rules and not the loader's
 * hurt-immunity bookkeeping.
 *
 * <p><b>Server-authoritative.</b> Only the server reads and writes these; a client's
 * hitstop is presentation (sub-16/17 consume the fact, never decide it).
 */
public interface CombatState {

    /** Whether {@code target} is currently invulnerable to the engine's pipeline. */
    boolean inIFrames(CombatActorRef target);

    /** Grants {@code target} invulnerability for {@code ticks}; the longer grant wins. */
    void grantIFrames(CombatActorRef target, int ticks);

    /** Freezes {@code target} for {@code ticks}: its brain and physics pause. */
    void applyHitstop(CombatActorRef target, int ticks);

    /** How many ticks of hitstop {@code target} has left. */
    int hitstopTicks(CombatActorRef target);

    /** Advances every counter by one tick — called once per server tick. */
    void tick();
}
