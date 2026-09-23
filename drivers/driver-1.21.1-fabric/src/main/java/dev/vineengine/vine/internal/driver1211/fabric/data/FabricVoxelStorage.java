package dev.vineengine.vine.internal.driver1211.fabric.data;

import com.mojang.serialization.Codec;

import net.minecraft.component.ComponentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.data.VoxelStorageBinding.EngineVoxels;
import dev.vineengine.vine.internal.driver1211.common.data.AbstractItemStackVoxelStorage;
import dev.vineengine.vine.internal.driver1211.common.data.VoxelProbe;

/**
 * Fabric 1.21.1 item-stack attach (sub-03 Stage D): the engine's
 * {@code vine:voxel_data} data component carries the tree's opaque blob, and the
 * native mapping resolves {@code minecraft:damage} to the vanilla DAMAGE
 * component.
 *
 * <p>The component type is registered in mod init (this cell's registration
 * moment, {@code Registry.register} through {@code FabricRegistryBuilder}).
 */
public final class FabricVoxelStorage extends AbstractItemStackVoxelStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FabricVoxelStorage.class);

    /** The engine's portable payload component. */
    // Payload is Base64 text, not raw bytes: data components must be immutable
    // values with equals/hashCode (NeoForge rejects byte[] outright, and the two
    // cells must agree byte-for-byte anyway).
    public static final ComponentType<String> VOXEL_DATA = ComponentType.<String>builder()
        .codec(Codec.STRING)
        .build();

    private static byte[] decode(String payload) {
        return payload == null ? null : java.util.Base64.getDecoder().decode(payload);
    }

    private static String encode(byte[] blob) {
        return java.util.Base64.getEncoder().encodeToString(blob);
    }

    public FabricVoxelStorage() {
    }

    /** A fresh stack of the testmod's item — the probe's attach target. */
    public static Object probeStack() {
        var item = dev.vineengine.vine.internal.driver1211.fabric.registry.FabricContentMaterializer
            .itemFor(dev.vineengine.vine.registry.VineId.of("vine_test", "testitem"));
        if (item == null) {
            throw new IllegalStateException("probe item vine_test:testitem was never materialized");
        }
        return new ItemStack(item);
    }

    /** Raw component access for the probe's own save/load simulation. */
    public static VoxelProbe.ComponentAccess probeAccess() {
        return new VoxelProbe.ComponentAccess() {
            @Override
            public byte[] read(Object stack) {
                return decode(((ItemStack) stack).get(VOXEL_DATA));
            }

            @Override
            public void write(Object stack, byte[] blob) {
                ((ItemStack) stack).set(VOXEL_DATA, encode(blob));
            }

            @Override
            public int nativeInt(Object stack, String componentId) {
                return ((ItemStack) stack).getOrDefault(DataComponentTypes.DAMAGE, 0);
            }
        };
    }

    /** Registers the component type; call during mod init, before any item is built. */
    public static void registerComponent() {
        net.minecraft.registry.Registry.register(
            Registries.DATA_COMPONENT_TYPE, Identifier.of("vine", "voxel_data"), VOXEL_DATA);
        LOG.info("[VINE] voxeldata: component vine:voxel_data registered (Fabric)");
    }

    @Override
    protected byte[] storedBlob(Object stack) {
        return decode(((ItemStack) stack).get(VOXEL_DATA));
    }

    @Override
    protected void storeBlob(Object stack, byte[] blob) {
        ((ItemStack) stack).set(VOXEL_DATA, encode(blob));
    }

    @Override
    protected int readNativeInt(Object stack, String componentId) {
        if (!VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            return Integer.MIN_VALUE;
        }
        return ((ItemStack) stack).getOrDefault(DataComponentTypes.DAMAGE, 0);
    }

    @Override
    protected boolean writeNativeInt(Object stack, String componentId, int value) {
        if (!VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            return false;
        }
        ((ItemStack) stack).set(DataComponentTypes.DAMAGE, value);
        return true;
    }
}
