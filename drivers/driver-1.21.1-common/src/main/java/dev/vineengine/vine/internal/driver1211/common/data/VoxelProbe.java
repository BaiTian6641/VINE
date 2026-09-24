package dev.vineengine.vine.internal.driver1211.common.data;

import java.util.Set;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.ItemStackTarget;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.capability.VineCapabilities;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.internal.data.VoxelStorageBinding;
import dev.vineengine.vine.registry.VineId;

/**
 * The sub-03 Stage D acceptance probe, run by each cell after its storage driver
 * is bound: a real item-stack round-trip (attach, mutate, flush, re-open through
 * the engine's public path) plus the native-strategy leg (a mapped path mirrors
 * the vanilla {@code minecraft:damage} component). One printed line per leg, so
 * the TCK scenario asserts identical behavior across loader families.
 *
 * <p>Schemas are registered during {@code REGISTRIES_OPEN} (the store freezes at
 * {@code REGISTRIES_FROZEN}); the probe itself runs at {@code SERVER_UP}.
 */
public final class VoxelProbe {

    public static final VineId ROUNDTRIP_SCHEMA = VineId.of("vine", "probe_roundtrip");
    public static final VineId NATIVE_SCHEMA = VineId.of("vine", "probe_native");
    public static final VineId CAP_SCHEMA = VineId.of("vine", "probe_cap_state");
    public static final String NATIVE_DAMAGE = "minecraft:damage";

    /**
     * Per-cell factory for the block-entity and entity attach legs (sub-03 Stage
     * E). Holders are real game objects created for the probe — never placed in
     * the world, so the probe cannot disturb a save — and {@code persisted}
     * reports whether the holder's own save data carries the engine payload,
     * which is the property that makes a tree survive a save/reload.
     */
    /**
     * A placed-block round-trip leg: the cell places the testmod's block, resolves
     * its block entity from the world, writes a tree through the engine API and —
     * on the next boot of the same world — reports the value that survived.
     */
    public interface PlacedBlockHolders extends AttachmentHolders {

        /** Places the testmod block if absent; returns the block entity, or null. */
        Object placedBlockEntity(VineId blockId);
    }

    public interface AttachmentHolders {

        Object blockEntity();

        Object entity();

        boolean persisted(Object holder);

        /** Cell label fragment for the log line (e.g. "NeoForge"). */
        String describe();
    }

    /** The probe's consumer-visible capability interface (sub-04 Stage C/D leg). */
    public interface ProbeCap {
        int value();

        void value(int value);
    }

    /** Stateful capability type whose state rides the item's engine payload. */
    public static final dev.vineengine.vine.capability.CapabilityType<ProbeCap> PROBE_CAP =
        dev.vineengine.vine.capability.CapabilityType.stateful(
            VineId.of("vine", "probe_cap"), ProbeCap.class,
            new dev.vineengine.vine.capability.CapabilityCodec<ProbeCap>() {
                @Override
                public VoxelData save(ProbeCap instance) {
                    VoxelData tree = VineData.create(CAP_SCHEMA);
                    tree.put("value", instance.value());
                    return tree;
                }

                @Override
                public ProbeCap load(VoxelData data) {
                    return new ProbeCap() {
                        @Override
                        public int value() {
                            return data.getInt("value");
                        }

                        @Override
                        public void value(int value) {
                            data.put("value", value);
                        }
                    };
                }
            }, dev.vineengine.vine.capability.ClonePolicy.FULL);

    private VoxelProbe() {
    }

    /** Registers the probe schemas and the native mapping; call before the freeze. */
    public static void registerSchemas() {
        VineData.registerSchema(new VoxelSchema(ROUNDTRIP_SCHEMA, 1, Codec.unit(null)), java.util.List.of());
        VineData.registerSchema(new VoxelSchema(NATIVE_SCHEMA, 1, Codec.unit(null)), java.util.List.of());
        VineData.registerNativeField(NATIVE_SCHEMA, "damage", NATIVE_DAMAGE);
        VineData.registerSchema(new VoxelSchema(CAP_SCHEMA, 1, Codec.unit(null)), java.util.List.of());
        PlacedBlockRoundTrip.registerSchema();
        VineCapabilities.register(PROBE_CAP);
        // Scope binding: stateful types answer through their state tree, so the
        // provider is never consulted — the attach marks which scopes the type
        // serves. All four scopes have attach points since sub-03 Stage E, so the
        // probe claims them all and the attach legs below exercise the ones that
        // need a holder object.
        for (dev.vineengine.vine.capability.CapabilityScope scope : java.util.List.of(
                dev.vineengine.vine.capability.CapabilityScope.ITEM,
                dev.vineengine.vine.capability.CapabilityScope.BLOCK,
                dev.vineengine.vine.capability.CapabilityScope.ENTITY,
                dev.vineengine.vine.capability.CapabilityScope.PLAYER)) {
            VineCapabilities.attach(PROBE_CAP, scope, target -> null);
        }
    }

    /** Runs both legs; {@code stackFactory} creates a probe stack on the calling cell. */
    public static void run(java.util.function.Supplier<Object> stackFactory, ComponentAccess access, String cell) {
        run(stackFactory, access, cell, null);
    }

    public static void run(java.util.function.Supplier<Object> stackFactory, ComponentAccess access, String cell,
                           AttachmentHolders holders) {
        // Leg 1 — portable round-trip through the engine's public attach path.
        Object stack = stackFactory.get();
        VoxelData tree = VineData.of(new ItemStackTarget(stack), ROUNDTRIP_SCHEMA);
        tree.put("mana", 42);
        tree.put("stats.kills", 3);
        flush(ROUNDTRIP_SCHEMA, stack);

        // Re-open on a copy carrying the stored payload: the load path runs for real.
        Object reopened = stackFactory.get();
        byte[] payload = access.read(stack);
        access.write(reopened, payload);
        VoxelData readBack = VineData.of(new ItemStackTarget(reopened), ROUNDTRIP_SCHEMA);
        System.out.println("[VINE] voxeldata roundtrip cell=" + cell);
        System.out.println("[VINE] voxeldata roundtrip values mana=" + readBack.getInt("mana")
            + " kills=" + readBack.getInt("stats.kills"));

        // Leg 2 — native strategy: the mapped path mirrors the vanilla component.
        Object nativeStack = stackFactory.get();
        VoxelData nativeTree = VineData.of(new ItemStackTarget(nativeStack), NATIVE_SCHEMA);
        nativeTree.put("damage", 7);
        flush(NATIVE_SCHEMA, nativeStack);
        System.out.println("[VINE] voxeldata native cell=" + cell);
        System.out.println("[VINE] voxeldata native value damage="
            + access.nativeInt(nativeStack, NATIVE_DAMAGE));

        // Leg 3 — capability interop: the instance writes through to the target's
        // attach point, and a copy of the target carries the capability with it,
        // readable through the native-query seam.
        Object capStack = stackFactory.get();
        var capTarget = new dev.vineengine.vine.capability.CapabilityTarget.ItemCapabilityTarget(capStack);
        ProbeCap cap = VineCapabilities.find(PROBE_CAP, capTarget)
            .orElseThrow(() -> new IllegalStateException("probe capability not served on this cell"));
        cap.value(11);
        VineCapabilities.flush(capTarget);

        Object capCopy = stackFactory.get();
        access.write(capCopy, access.read(capStack));
        var copiedTarget = new dev.vineengine.vine.capability.CapabilityTarget.ItemCapabilityTarget(capCopy);
        var foreign = VineCapabilities.findForeign(PROBE_CAP.id(), VoxelData.class, copiedTarget);
        System.out.println("[VINE] caps driver interop cell=" + cell);
        System.out.println("[VINE] caps driver interop value="
            + foreign.map(carrier -> carrier.getInt("value")).orElse(-1));

        // Leg 4 — block-entity and entity attach (Stage E): the same engine API,
        // the same blob, a different holder kind per cell's attachment mechanism.
        if (holders == null) {
            return;
        }
        Object blockEntity = holders.blockEntity();
        var beTarget = new dev.vineengine.vine.data.BlockEntityTarget(blockEntity);
        VoxelData beTree = VineData.of(beTarget, ROUNDTRIP_SCHEMA);
        beTree.put("mana", 77);
        flushTree(ROUNDTRIP_SCHEMA, beTarget, beTree);
        int beReadBack = VineData.of(new dev.vineengine.vine.data.BlockEntityTarget(blockEntity),
            ROUNDTRIP_SCHEMA).getInt("mana");
        boolean bePersisted = holders.persisted(blockEntity);

        Object entity = holders.entity();
        var entityTarget = new dev.vineengine.vine.data.EntityTarget(entity);
        VoxelData entityTree = VineData.of(entityTarget, ROUNDTRIP_SCHEMA);
        entityTree.put("mana", 88);
        flushTree(ROUNDTRIP_SCHEMA, entityTarget, entityTree);
        int entityReadBack = VineData.of(new dev.vineengine.vine.data.EntityTarget(entity),
            ROUNDTRIP_SCHEMA).getInt("mana");
        boolean entityPersisted = holders.persisted(entity);

        // Two schemas on ONE holder: the bundle shape exists so a second schema
        // (a capability state, say) cannot silently clobber the first.
        Object shared = holders.blockEntity();
        var sharedTarget = new dev.vineengine.vine.data.BlockEntityTarget(shared);
        VoxelData first = VineData.of(sharedTarget, ROUNDTRIP_SCHEMA);
        first.put("mana", 11);
        flushTree(ROUNDTRIP_SCHEMA, sharedTarget, first);
        VoxelData second = VineData.of(sharedTarget, NATIVE_SCHEMA);
        second.put("damage", 22);
        flushTree(NATIVE_SCHEMA, sharedTarget, second);
        int firstAfter = VineData.of(sharedTarget, ROUNDTRIP_SCHEMA).getInt("mana");
        int secondAfter = VineData.of(sharedTarget, NATIVE_SCHEMA).getInt("damage");
        System.out.println("[VINE] voxeldata bundle cell=" + cell + " firstSchema=" + firstAfter
            + " secondSchema=" + secondAfter);

        // Capability scopes beyond items (sub-04 Stage E): the same capability
        // state rides a block entity's and an entity's attachment payload, found
        // and flushed through the engine API with no scope-specific consumer code.
        var beCapTarget = new dev.vineengine.vine.capability.CapabilityTarget.BlockCapabilityTarget(
            holders.blockEntity());
        ProbeCap beCap = VineCapabilities.find(PROBE_CAP, beCapTarget).orElse(null);
        int beCapValue = -1;
        if (beCap != null) {
            beCap.value(31);
            VineCapabilities.flush(beCapTarget);
            beCapValue = VineCapabilities.find(PROBE_CAP, beCapTarget).map(ProbeCap::value).orElse(-2);
        }
        var entityCapTarget = new dev.vineengine.vine.capability.CapabilityTarget.EntityCapabilityTarget(
            holders.entity());
        ProbeCap entityCap = VineCapabilities.find(PROBE_CAP, entityCapTarget).orElse(null);
        int entityCapValue = -1;
        if (entityCap != null) {
            entityCap.value(41);
            VineCapabilities.flush(entityCapTarget);
            entityCapValue = VineCapabilities.find(PROBE_CAP, entityCapTarget).map(ProbeCap::value).orElse(-2);
        }
        System.out.println("[VINE] caps scopes cell=" + cell + " block=" + beCapValue
            + " entity=" + entityCapValue);

        // Placed-block leg (sub-07 Stage C / sub-22 Stage B): write through a real
        // block entity in the world, and on a later boot report what survived —
        // the acceptance's place -> write -> save/reload -> assert path, run
        // across two server runs.
        System.out.println("[VINE] voxeldata placed block cell=" + cell + " "
            + PlacedBlockRoundTrip.run(holders));

        System.out.println("[VINE] voxeldata attach cell=" + cell + " holders=" + holders.describe());
        System.out.println("[VINE] voxeldata attach blockentity mana=" + beReadBack
            + " persisted=" + bePersisted + " entity mana=" + entityReadBack
            + " persisted=" + entityPersisted);
    }

    private static void flush(VineId schemaId, Object holder) {
        var driver = VoxelStorageBinding.bound();
        if (driver == null) {
            throw new IllegalStateException("voxeldata probe ran before a VoxelStorageDriver was bound");
        }
        flush(schemaId, new ItemStackTarget(holder), null, driver);
    }

    /** Flushes one tree through its target using the tree's own dirty record. */
    private static void flushTree(VineId schemaId, dev.vineengine.vine.data.VoxelTarget target, VoxelData tree) {
        var driver = VoxelStorageBinding.bound();
        if (driver == null) {
            throw new IllegalStateException("voxeldata probe ran before a VoxelStorageDriver was bound");
        }
        driver.flushDirty(target, schemaId, VoxelStorageBinding.engine().drainDirty(tree));
    }

    private static void flush(VineId schemaId, dev.vineengine.vine.data.VoxelTarget target,
                              VoxelData tree, dev.vineengine.vine.internal.spi.VoxelStorageDriver driver) {
        // Stage E: the flush carries the dirty set the tree recorded, not a
        // wildcard — the same record a sync pass would transmit.
        Set<String> dirty = tree == null ? Set.of("*")
            : VoxelStorageBinding.engine().drainDirty(tree);
        driver.flushDirty(target, schemaId, dirty);
    }

    /** Raw component access — the probe's own plumbing on a cell. */
    public interface ComponentAccess {

        byte[] read(Object stack);

        void write(Object stack, byte[] blob);

        int nativeInt(Object stack, String componentId);
    }
}
