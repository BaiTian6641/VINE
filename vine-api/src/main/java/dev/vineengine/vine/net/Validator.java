package dev.vineengine.vine.net;

import dev.vineengine.vine.VinePlayer;

/**
 * One step in a C2S message's validation chain (sub-05 Stage D): every inbound
 * client payload runs codec bounds → validators → handler, all on the server
 * thread. A validator never throws for control flow — it returns a
 * {@link Verdict}, and the engine turns that into the documented action
 * (drop with one structured log line; a kick request is reported and, until the
 * player facade exposes disconnect, degrades to a drop).
 */
@FunctionalInterface
public interface Validator<P> {

    /** What the engine should do with a validated payload. */
    enum Verdict {
        /** Run the next validator, then the handler. */
        ACCEPT,
        /** Drop the payload: one structured log line, nothing else. */
        REJECT,
        /** Drop the payload and request a kick (degraded to a drop until the player facade lands). */
        KICK
    }

    /** Validates {@code payload} from {@code sender}. Called on the server thread. */
    Verdict validate(P payload, VinePlayer sender);
}
