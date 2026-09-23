package dev.vineengine.vine.internal.driver1211.common.data;

import java.util.Set;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.data.ItemStackTarget;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
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
    public static final String NATIVE_DAMAGE = "minecraft:damage";

    private VoxelProbe() {
    }

    /** Registers the probe schemas and the native mapping; call before the freeze. */
    public static void registerSchemas() {
        VineData.registerSchema(new VoxelSchema(ROUNDTRIP_SCHEMA, 1, Codec.unit(null)), java.util.List.of());
        VineData.registerSchema(new VoxelSchema(NATIVE_SCHEMA, 1, Codec.unit(null)), java.util.List.of());
        VineData.registerNativeField(NATIVE_SCHEMA, "damage", NATIVE_DAMAGE);
    }

    /** Runs both legs; {@code stackFactory} creates a probe stack on the calling cell. */
    public static void run(java.util.function.Supplier<Object> stackFactory, ComponentAccess access, String cell) {
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
    }

    private static void flush(VineId schemaId, Object stack) {
        var driver = VoxelStorageBinding.bound();
        if (driver == null) {
            throw new IllegalStateException("voxeldata probe ran before a VoxelStorageDriver was bound");
        }
        // Whole-tree flush: M1 has no path-granular dirty tracking yet (sub-03 Stage E).
        driver.flushDirty(new ItemStackTarget(stack), schemaId, Set.of("*"));
    }

    /** Raw component access — the probe's own plumbing on a cell. */
    public interface ComponentAccess {

        byte[] read(Object stack);

        void write(Object stack, byte[] blob);

        int nativeInt(Object stack, String componentId);
    }
}
