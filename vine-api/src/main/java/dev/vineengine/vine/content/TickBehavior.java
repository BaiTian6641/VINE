package dev.vineengine.vine.content;

/**
 * A behavior that runs on the engine's normalized block tick (sub-07 Stage C):
 * random ticks and scheduled ticks are one signal here, and a block entity that
 * asked to tick arrives through the same interface with its interval already
 * applied by the cell.
 *
 * <p>Ticking is opt-in twice over: a descriptor must carry a {@code TickBehavior}
 * <em>and</em>, for a block entity, {@code BlockEntityDescriptor.ticking()} must be
 * true. A block that declares neither installs no ticker on any cell, which is what
 * the boot assertion for this stage counts.
 */
public non-sealed interface TickBehavior extends BlockBehavior {

    /** Runs one tick for the block at {@code ctx}'s position. */
    void tick(BlockTickContext ctx);
}
