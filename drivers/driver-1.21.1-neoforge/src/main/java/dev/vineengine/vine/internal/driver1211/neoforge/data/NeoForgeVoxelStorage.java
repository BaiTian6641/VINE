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
import dev.vineengine.vine.internal.driver1211.common.data.AbstractVoxelStorage;
import dev.vineengine.vine.internal.driver1211.common.data.VoxelProbe;

/**
 * NeoForge 1.21.1 attach points (sub-03 Stages D + E): the engine's
 * {@code vine:voxel_data} data component carries an item stack's blob, and the
 * same payload rides a {@code vine:voxel_data} **attachment** on block entities,
 * entities and players — NeoForge patches those holders with
 * {@code IAttachmentHolder}, so attach needs no engine Mixin here either.
 *
 * <p>Attachments are serialized into the holder's own save data (the vanilla
 * contract for {@code IAttachmentSerializer}), which is what makes a tree survive
 * a save/reload. Sync is deliberately not declared yet: only client-visible paths
 * should cross to clients, and no path carries that flag.
 *
 * <p>Both registrations are declared through {@code DeferredRegister}s created in
 * the mod constructor (this cell's {@code REGISTRIES_OPEN} moment).
 */
public final class NeoForgeVoxelStorage extends AbstractVoxelStorage {

    private static final Logger LOG = LoggerFactory.getLogger(NeoForgeVoxelStorage.class);

    private static DeferredRegister<DataComponentType<?>> components;
    private static DeferredHolder<DataComponentType<?>, DataComponentType<String>> voxelData;
    private static DeferredRegister<net.neoforged.neoforge.attachment.AttachmentType<?>> attachments;
    private static DeferredHolder<net.neoforged.neoforge.attachment.AttachmentType<?>,
        net.neoforged.neoforge.attachment.AttachmentType<String>> voxelAttachment;

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

    /**
     * The Stage-E attach legs: a vanilla block entity and a vanilla entity
     * created for the probe and never placed in the world, so a probe can never
     * disturb a save. The testmod's own block entity arrives with sub-22 Stage B.
     */
    public static VoxelProbe.AttachmentHolders probeHolders() {
        return new VoxelProbe.AttachmentHolders() {
            @Override
            public Object blockEntity() {
                var type = net.minecraft.world.level.block.entity.BlockEntityType.CHEST;
                return type.create(net.minecraft.core.BlockPos.ZERO,
                    net.minecraft.world.level.block.Blocks.CHEST.defaultBlockState());
            }

            @Override
            public Object entity() {
                net.minecraft.server.MinecraftServer server =
                    dev.vineengine.vine.internal.driver1211.neoforge.boot.NeoForge1211Driver.currentServer();
                if (server == null) {
                    throw new IllegalStateException("probe entity requested before the server was available");
                }
                return net.minecraft.world.entity.EntityType.PIG.create(server.overworld());
            }

            @Override
            public boolean persisted(Object holder) {
                // The attachment rides the holder's own save data (the vanilla
                // contract for IAttachmentSerializer) — checking the live map
                // would only prove memory.
                net.minecraft.server.MinecraftServer server =
                    dev.vineengine.vine.internal.driver1211.neoforge.boot.NeoForge1211Driver.currentServer();
                if (server == null) {
                    return false;
                }
                try {
                    net.minecraft.nbt.CompoundTag tag;
                    if (holder instanceof net.minecraft.world.level.block.entity.BlockEntity blockEntity) {
                        tag = blockEntity.saveWithoutMetadata(server.registryAccess());
                    } else if (holder instanceof net.minecraft.world.entity.Entity entity) {
                        tag = new net.minecraft.nbt.CompoundTag();
                        entity.saveWithoutId(tag);
                    } else {
                        return false;
                    }
                    net.minecraft.nbt.Tag attachments = tag.get("neoforge:attachments");
                    return attachments != null && attachments.toString().contains("vine:voxel_data");
                } catch (RuntimeException saveFailure) {
                    return false;
                }
            }

            @Override
            public String describe() {
                return "vanilla chest be + pig entity (neoforge attachments)";
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

        attachments = DeferredRegister.create(
            net.neoforged.neoforge.registries.NeoForgeRegistries.ATTACHMENT_TYPES, "vine");
        voxelAttachment = attachments.register("voxel_data", () -> net.neoforged.neoforge.attachment
            .AttachmentType.<String>builder(() -> "")
            .serialize(new net.neoforged.neoforge.attachment.IAttachmentSerializer<net.minecraft.nbt.StringTag, String>() {
                @Override
                public net.minecraft.nbt.StringTag write(String value,
                        net.minecraft.core.HolderLookup.Provider registries) {
                    return net.minecraft.nbt.StringTag.valueOf(value);
                }

                @Override
                public String read(net.neoforged.neoforge.attachment.IAttachmentHolder holder,
                        net.minecraft.nbt.StringTag tag, net.minecraft.core.HolderLookup.Provider registries) {
                    return tag.getAsString();
                }
            })
            .build());
        attachments.register(modBus);
        LOG.info("[VINE] voxeldata: component and attachment vine:voxel_data declared (NeoForge)");
    }

    /** The attachment holder behind a block entity / entity / player object. */
    private static net.neoforged.neoforge.attachment.IAttachmentHolder attachmentOf(Object holder) {
        if (holder instanceof net.neoforged.neoforge.attachment.IAttachmentHolder target) {
            return target;
        }
        throw new UnsupportedOperationException(
            "this cell cannot attach voxel data to " + holder.getClass().getName());
    }

    @Override
    protected byte[] storedBlob(Object holder) {
        if (holder instanceof ItemStack stack) {
            String payload = stack.get(voxelData.get());
            return payload == null ? null : java.util.Base64.getDecoder().decode(payload);
        }
        String payload = attachmentOf(holder).getExistingDataOrNull(voxelAttachment.get());
        return payload == null ? null : java.util.Base64.getDecoder().decode(payload);
    }

    @Override
    protected void storeBlob(Object holder, byte[] blob) {
        String payload = java.util.Base64.getEncoder().encodeToString(blob);
        if (holder instanceof ItemStack stack) {
            stack.set(voxelData.get(), payload);
            return;
        }
        attachmentOf(holder).setData(voxelAttachment.get(), payload);
    }

    @Override
    protected int readNativeInt(Object holder, String componentId) {
        if (!(holder instanceof ItemStack stack) || !VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            // Native interop is an item-stack mapping in this stage.
            return Integer.MIN_VALUE;
        }
        return stack.getOrDefault(DataComponents.DAMAGE, 0);
    }

    @Override
    protected boolean writeNativeInt(Object holder, String componentId, int value) {
        if (!(holder instanceof ItemStack stack) || !VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            return false;
        }
        stack.set(DataComponents.DAMAGE, value);
        return true;
    }
}
