package dev.vineengine.vine.testmod.capability;

import java.util.List;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.capability.CapabilityCodec;
import dev.vineengine.vine.capability.CapabilityScope;
import dev.vineengine.vine.capability.CapabilityTarget;
import dev.vineengine.vine.capability.CapabilityType;
import dev.vineengine.vine.capability.ClonePolicy;
import dev.vineengine.vine.capability.VineCapabilities;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.registry.VineId;

/**
 * Capability exemplar (sub-04, sub-22 content contract: one canonical
 * exemplar per surface): one stateful custom capability type
 * {@code vine_test:mana} with a VoxelData codec, attached to the ITEM scope,
 * proving the fallback store end-to-end — register → attach → find (through
 * the real engine store) → mutate → clone (policy FULL) → codec round-trip.
 *
 * <p>Consumer-pure by construction: engine types only (no game classes — the
 * synthetic target's payload is an opaque {@code Object}, the interim-seam
 * shape), identical sources on every cell.
 */
public final class CapabilityExemplar {

    /** The capability interface consumers see (sub-04 §2 shape). */
    public interface Mana {

        int amount();

        void setAmount(int amount);
    }

    /** Schema backing the codec's detached trees (the store owns its own). */
    private static final VineId SCHEMA_ID = VineId.of("vine_test", "mana_state");

    /** Placeholder codec — blobs route through the engine's wire codec (§5.4). */
    private static final Codec<VoxelData> TREE_CODEC = Codec.unit(null);

    /** Write-through instance: state lives in the backing tree, not the object. */
    private record ManaImpl(VoxelData tree) implements Mana {
        @Override
        public int amount() {
            return tree.getInt("amount");
        }

        @Override
        public void setAmount(int amount) {
            tree.put("amount", amount);
        }
    }

    private static final CapabilityCodec<Mana> CODEC = new CapabilityCodec<>() {
        @Override
        public VoxelData save(Mana instance) {
            VoxelData tree = VineData.create(SCHEMA_ID);
            tree.put("amount", instance.amount());
            return tree;
        }

        @Override
        public Mana load(VoxelData data) {
            return new ManaImpl(data);
        }
    };

    public static final CapabilityType<Mana> MANA_TYPE =
        CapabilityType.stateful(VineId.of("vine_test", "mana"), Mana.class, CODEC, ClonePolicy.FULL);

    private CapabilityExemplar() {
    }

    /** Registers the schema, type, and ITEM-scope provider (called at init). */
    public static void register() {
        VineData.registerSchema(new VoxelSchema(SCHEMA_ID, 1, TREE_CODEC), List.of());
        VineCapabilities.register(MANA_TYPE);
        VineCapabilities.attach(MANA_TYPE, CapabilityScope.ITEM,
            target -> new ManaImpl(VineData.create(SCHEMA_ID)));
    }

    /**
     * The TCK proof (driven by {@code vine_test tck_caps}): exercises the real
     * engine store — find → mutate → re-find (cache) → clone (FULL) → codec
     * round-trip — and prints one line per check for scenario assertions.
     */
    public static void runProof() {
        CapabilityTarget target = new CapabilityTarget.ItemCapabilityTarget("tck-synthetic-item");
        Mana mana = VineCapabilities.find(MANA_TYPE, target)
            .orElseThrow(() -> new IllegalStateException("mana capability not attached for ITEM scope"));
        mana.setAmount(42);

        Mana again = VineCapabilities.find(MANA_TYPE, target)
            .orElseThrow(() -> new IllegalStateException("mana capability vanished on re-find"));
        if (again != mana || again.amount() != 42) {
            throw new IllegalStateException("capability cache unstable: same=" + (again == mana)
                + " amount=" + again.amount());
        }
        System.out.println("vine-testmod: capability cache stable mana=42");

        CapabilityTarget cloned = new CapabilityTarget.ItemCapabilityTarget("tck-synthetic-item-clone");
        VineCapabilities.applyClone(MANA_TYPE, target, cloned);
        int clonedAmount = VineCapabilities.find(MANA_TYPE, cloned)
            .orElseThrow(() -> new IllegalStateException("cloned capability missing"))
            .amount();
        if (clonedAmount != 42) {
            throw new IllegalStateException("FULL clone lost state: " + clonedAmount);
        }
        System.out.println("vine-testmod: capability clone FULL mana=42");

        Mana roundTrip = MANA_TYPE.state().load(MANA_TYPE.state().save(mana));
        if (roundTrip.amount() != 42) {
            throw new IllegalStateException("codec round-trip lost state: " + roundTrip.amount());
        }
        System.out.println("vine-testmod: capability roundtrip mana=42");
    }
}
