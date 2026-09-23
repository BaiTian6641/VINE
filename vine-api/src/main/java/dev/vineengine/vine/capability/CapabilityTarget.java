package dev.vineengine.vine.capability;

import dev.vineengine.vine.VinePlayer;

/**
 * The four attach points a capability instance can live on (sub-04 §2).
 *
 * <p><b>Interim seam:</b> block/entity/item targets carry the raw per-cell
 * object until the sub-07/08/13 facades land (same pattern and rationale as
 * {@code VoxelTarget}) — no game type appears in any signature; drivers
 * interpret the payload only inside their own jars. Player targets wrap the
 * engine's {@link VinePlayer} directly because that facade already exists.
 */
public sealed interface CapabilityTarget permits CapabilityTarget.BlockCapabilityTarget,
        CapabilityTarget.EntityCapabilityTarget, CapabilityTarget.ItemCapabilityTarget,
        CapabilityTarget.PlayerCapabilityTarget {

    /** The kind of attach point this target is. */
    CapabilityScope scope();

    /** Raw payload — the per-cell object, or the {@link VinePlayer}. */
    Object raw();

    record BlockCapabilityTarget(Object blockEntity) implements CapabilityTarget {
        @Override
        public CapabilityScope scope() {
            return CapabilityScope.BLOCK;
        }

        @Override
        public Object raw() {
            return blockEntity;
        }
    }

    record EntityCapabilityTarget(Object entity) implements CapabilityTarget {
        @Override
        public CapabilityScope scope() {
            return CapabilityScope.ENTITY;
        }

        @Override
        public Object raw() {
            return entity;
        }
    }

    record ItemCapabilityTarget(Object itemStack) implements CapabilityTarget {
        @Override
        public CapabilityScope scope() {
            return CapabilityScope.ITEM;
        }

        @Override
        public Object raw() {
            return itemStack;
        }
    }

    record PlayerCapabilityTarget(VinePlayer player) implements CapabilityTarget {
        @Override
        public CapabilityScope scope() {
            return CapabilityScope.PLAYER;
        }

        @Override
        public Object raw() {
            return player;
        }
    }
}
