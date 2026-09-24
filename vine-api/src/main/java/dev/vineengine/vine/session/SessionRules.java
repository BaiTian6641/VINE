package dev.vineengine.vine.session;

import java.util.UUID;

/**
 * Server-only authority over one session (sub-14 §2). Never replicated;
 * clients request, rules decide. The engine owns lifecycle transitions and
 * calls back into the rules — a consumer's rules are pure policy, never
 * lifecycle plumbing.
 */
public abstract class SessionRules {

    /** Session has been created (still {@code CREATED}). */
    protected void onCreated(SessionContext ctx) {
    }

    /** Every action goes through here; the verdict is final. */
    protected abstract ActionVerdict validate(SessionAction action, SessionContext ctx);

    /** A participant joined or re-joined (re-join tolerant). */
    protected void onParticipantJoined(SessionContext ctx, UUID player) {
    }

    /**
     * Whether participant states are public among participants (sub-14 §2). The
     * default keeps them owner-only: a co-op hunt shows the leader's plan, not
     * every participant's private progress.
     */
    protected boolean publicParticipantState() {
        return false;
    }

    /** A lifecycle transition happened; {@code next} is the new phase. */
    protected void onPhaseAdvanced(SessionContext ctx, SessionPhase next) {
    }

    // ------------------------------------------------------------------
    // Engine-internal chords: the session service is the only caller.
    // ------------------------------------------------------------------

    /** @see #onCreated(SessionContext) */
    public final void created(SessionContext ctx) {
        onCreated(ctx);
    }

    /** @see #validate(SessionAction, SessionContext) */
    public final ActionVerdict decide(SessionAction action, SessionContext ctx) {
        return validate(action, ctx);
    }

    /** @see #onParticipantJoined(SessionContext, UUID) */
    public final void joined(SessionContext ctx, UUID player) {
        onParticipantJoined(ctx, player);
    }

    /** @see #publicParticipantState() */
    public final boolean participantStatesPublic() {
        return publicParticipantState();
    }

    /** @see #onPhaseAdvanced(SessionContext, SessionPhase) */
    public final void phaseAdvanced(SessionContext ctx, SessionPhase next) {
        onPhaseAdvanced(ctx, next);
    }
}
