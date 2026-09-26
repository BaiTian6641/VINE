package dev.vineengine.vine.quest;

import com.mojang.serialization.Codec;

/**
 * A registrable objective kind (sub-15 §2, §5.20): its parameters' codec and the pure
 * behaviour that turns events into counts. Consumers register their own kinds without
 * touching engine code; the engine ships a small set for the common shapes.
 *
 * @param paramsCodec how this type's parameters are read from JSON and carried in the tree
 * @param behavior    how an event advances an objective of this type
 */
public record ObjectiveType<P>(Codec<P> paramsCodec, ObjectiveBehavior<P> behavior) {
}
