package dev.vineengine.vine.quest;

import dev.vineengine.vine.data.VoxelData;

/**
 * What an objective type does when an event arrives (sub-15 §2, §5.20).
 *
 * <p><b>Pure decisions, engine bookkeeping.</b> A behaviour answers "how much did this
 * event advance this objective" and nothing else: it never writes progress, never grants
 * rewards and never talks to a world. That is what keeps quest progress a single
 * authoritative value the engine stores, and what makes a quest chain testable without a
 * server in the loop.
 */
@FunctionalInterface
public interface ObjectiveBehavior<P> {

    /**
     * How much {@code event} advances an objective with {@code params}.
     *
     * @param event the event's own payload (a kill's entity id, an item's id, an amount)
     * @return the increment; {@code 0} means this event is irrelevant to this objective
     */
    int advance(P params, VoxelData event);
}
