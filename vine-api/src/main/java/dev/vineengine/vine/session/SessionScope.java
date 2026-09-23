package dev.vineengine.vine.session;

import dev.vineengine.vine.registry.VineId;

/**
 * The scope a session lives in (sub-14 §2): a world, or a party.
 */
public sealed interface SessionScope permits SessionScope.World, SessionScope.Party {

    /** Which kind of scope — mirrors {@code SessionTypeDescriptor.defaultScope}. */
    enum Kind { WORLD, PARTY }

    Kind kind();

    /** Per-world scoping (activities in a dimension/level). */
    record World(VineId worldId) implements SessionScope {
        @Override
        public Kind kind() {
            return Kind.WORLD;
        }
    }

    /** Per-party scoping (members share; allies do not). */
    record Party(PartyRef party) implements SessionScope {
        @Override
        public Kind kind() {
            return Kind.PARTY;
        }
    }
}
