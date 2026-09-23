package dev.vineengine.vine.internal.driver1211.neoforge.data;

import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.internal.data.VoxelStorageBinding.EngineVoxels;
import dev.vineengine.vine.internal.driver1211.common.data.AbstractItemStackVoxelStorage;
import dev.vineengine.vine.internal.driver1211.common.data.VoxelProbe;

/**
 * NeoForge 1.21.1 item-stack attach (sub-03 Stage D): the engine's
 * {@code vine:voxel_data} data component carries the tree's opaque blob, and the
 * native mapping resolves {@code minecraft:damage} to the vanilla DAMAGE
 * component.
 *
 * <p>The component type is declared through a {@code DeferredRegister} created in
 * the mod constructor (this cell's {@code REGISTRIES_OPEN} moment).
 */
public final class NeoForgeVoxelStorage extends AbstractItemStackVoxelStorage {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeVoxelStorage.class);

    private static DeferredRegister<DataComponentType<?>> components;
    private static DeferredHolder<DataComponentType<?>, DataComponentType<String>> voxelData;

    /** A fresh stack of the testmod's item — the probe's attach target. */
    public static Object probeStack() {
        var item = dev.vineengine.vine.internal.driver1211.neoforge.registry.NeoForgeContentMaterializer
            .itemFor(dev.vineengine.vine.registry.VineId.of("vine_test", "testitem"));
        if (item == null) {
            throw new IllegalStateException("probe item vine_test:testitem was never materialized");
        }
        return new ItemStack(item);
    }

    /** The stored payload bytes on {@code stack} (capability interop carrier). */
    public static byte[] rawPayload(Object stack) {
        String payload = ((ItemStack) stack).get(voxelData.get());
        return payload == null ? null : java.util.Base64.getDecoder().decode(payload);
    }

    /** Raw component access for the probe's own save/load simulation. */
    public static VoxelProbe.ComponentAccess probeAccess() {
        return new VoxelProbe.ComponentAccess() {
            @Override
            public byte[] read(Object stack) {
                String payload = ((ItemStack) stack).get(voxelData.get());
                return payload == null ? null : java.util.Base64.getDecoder().decode(payload);
            }

            @Override
            public void write(Object stack, byte[] blob) {
                ((ItemStack) stack).set(voxelData.get(), java.util.Base64.getEncoder().encodeToString(blob));
            }

            @Override
            public int nativeInt(Object stack, String componentId) {
                return ((ItemStack) stack).getOrDefault(DataComponents.DAMAGE, 0);
            }
        };
    }

    /** Declares the component type on the mod bus; call during mod construction. */
    public static void registerComponent(IEventBus modBus) {
        components = DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, "vine");
        // Base64 text, not raw bytes: NF requires component payloads to be
        // immutable values with equals/hashCode, and both cells must agree.
        voxelData = components.register("voxel_data", () -> DataComponentType.<String>builder()
            .persistent(Codec.STRING)
            .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8)
            .build());
        components.register(modBus);
        LOG.info("[VINE] voxeldata: component vine:voxel_data declared (NeoForge)");
    }

    @Override
    protected byte[] storedBlob(Object stack) {
        String payload = ((ItemStack) stack).get(voxelData.get());
        return payload == null ? null : java.util.Base64.getDecoder().decode(payload);
    }

    @Override
    protected void storeBlob(Object stack, byte[] blob) {
        ((ItemStack) stack).set(voxelData.get(), java.util.Base64.getEncoder().encodeToString(blob));
    }

    @Override
    protected int readNativeInt(Object stack, String componentId) {
        if (!VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            return Integer.MIN_VALUE;
        }
        return ((ItemStack) stack).getOrDefault(DataComponents.DAMAGE, 0);
    }

    @Override
    protected boolean writeNativeInt(Object stack, String componentId, int value) {
        if (!VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            return false;
        }
        ((ItemStack) stack).set(DataComponents.DAMAGE, value);
        return true;
    }
}
