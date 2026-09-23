package dev.vineengine.vine.session;

import dev.vineengine.vine.registry.VineId;

/**
 * A stable party handle shared across subsystem APIs (sub-14 §2) — quest
 * progress (sub-15) keys by it; the party store itself (sub-14 party
 * primitive) resolves it to membership/ranks.
 */
public record PartyRef(VineId partyId) {
}
