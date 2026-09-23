package dev.vineengine.vine.session;

import java.util.Set;
import java.util.UUID;

/**
 * Read-side context handed to {@link SessionRules} callbacks (sub-14 §2):
 * everything the rules need to decide, nothing they can mutate.
 */
public interface SessionContext {

    VineSession session();

    SessionState state();

    Set<UUID> participants();
}
