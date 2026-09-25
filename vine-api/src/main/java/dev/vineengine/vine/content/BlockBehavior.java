package dev.vineengine.vine.content;

/**
 * One block behavior as data (sub-07 Stage C, plan §5.3): composition over
 * inheritance. A descriptor carries an <em>ordered</em> list of these; a cell wires
 * the native dispatch a behavior family can serve only when at least one behavior
 * of that family is present, and wires nothing at all when the list is empty
 * (Minimal Footprint — a block with no behaviors installs no hook, no ticker and no
 * loot modifier on any cell).
 *
 * <p><b>Sealed on purpose:</b> the family is closed — {@link UseBehavior},
 * {@link TickBehavior}, {@link LootBehavior} and whatever a later stage adds are
 * the engine's whole behavior vocabulary, so a cell can enumerate the families it
 * must serve and a consumer can never smuggle loader-specific behavior through the
 * API. The interfaces are {@code non-sealed} so a consumer may implement them
 * directly; what stays closed is the set of families, not their implementations.
 *
 * <p><b>Invariants:</b> a behavior is stateless with respect to the engine — its
 * state lives in the descriptor (authoring data) or in the holder's {@code VoxelData}
 * tree, never in the behavior instance, because one instance serves every placement
 * of its block on every world. Instances are expected to be immutable and
 * thread-safe; the engine never synchronizes calls into them beyond what the
 * callers' own threading guarantees (block ticks and interactions arrive on the
 * server thread).
 */
public sealed interface BlockBehavior permits UseBehavior, TickBehavior, LootBehavior {
}
