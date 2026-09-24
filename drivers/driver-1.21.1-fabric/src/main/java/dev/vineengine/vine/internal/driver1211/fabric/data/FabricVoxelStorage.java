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
import dev.vineengine.vine.internal.driver1211.common.data.AbstractVoxelStorage;
import dev.vineengine.vine.internal.driver1211.common.data.VoxelProbe;

/**
 * Fabric 1.21.1 attach points (sub-03 Stages D + E): the engine's
 * {@code vine:voxel_data} data component carries an item stack's blob, and the
 * same payload rides {@code vine:voxel_data} **data attachments** for block
 * entities, entities and players — Fabric's attachment API is mixed into all
 * three holder kinds by the mod whose semantics the engine borrows, so no engine
 * Mixin is needed for attach (sub-18's quarantine stays empty here).
 *
 * <p>Attachments are persistent but not synced in this stage: only
 * client-visible paths are supposed to cross to clients, and no path carries that
 * flag yet — registering a sync handler now would ship the whole tree.
 *
 * <p>The component type and the attachment type are registered in mod init (this
 * cell's registration moment, {@code Registry.register}).
 */
public final class FabricVoxelStorage extends AbstractVoxelStorage {

    private static final Logger LOG = LoggerFactory.getLogger(FabricVoxelStorage.class);

    /** The engine's portable payload component. */
    // Payload is Base64 text, not raw bytes: data components must be immutable
    // values with equals/hashCode (NeoForge rejects byte[] outright, and the two
    // cells must agree byte-for-byte anyway).
    public static final ComponentType<String> VOXEL_DATA = ComponentType.<String>builder()
        .codec(Codec.STRING)
        .build();

    /** The engine's portable payload attachment for block entities, entities and players. */
    public static final net.fabricmc.fabric.api.attachment.v1.AttachmentType<String> VOXEL_ATTACHMENT =
        net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry
            .createPersistent(Identifier.of("vine", "voxel_data"), Codec.STRING);

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

    /**
     * The stored payload bytes for any holder (item stack, block entity, entity,
     * player) — the capability driver's carrier view.
     */
    public static byte[] payloadOf(Object holder) {
        if (holder instanceof ItemStack stack) {
            return rawPayload(stack);
        }
        String payload = "" + (holder instanceof net.fabricmc.fabric.api.attachment.v1.AttachmentTarget target
            ? target.getAttached(VOXEL_ATTACHMENT) : null);
        return payload == null || payload.equals("null") ? null
            : java.util.Base64.getDecoder().decode(payload);
    }

    /** The stored payload bytes on an item stack (capability interop carrier). */
    public static byte[] rawPayload(Object stack) {
        return decode(((ItemStack) stack).get(VOXEL_DATA));
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

    /**
     * The Stage-E attach legs: a vanilla block entity and a vanilla entity
     * created for the probe and never placed in the world, so a probe can never
     * disturb a save. The testmod's own block entity arrives with sub-22 Stage B;
     * attach is exercised here on holders that exist in every cell.
     */
    public static VoxelProbe.AttachmentHolders probeHolders() {
        return new VoxelProbe.AttachmentHolders() {
            @Override
            public Object blockEntity() {
                return new net.minecraft.block.entity.ChestBlockEntity(
                    net.minecraft.util.math.BlockPos.ORIGIN,
                    net.minecraft.block.Blocks.CHEST.getDefaultState());
            }

            @Override
            public Object entity() {
                net.minecraft.server.MinecraftServer server =
                    dev.vineengine.vine.internal.driver1211.fabric.boot.Fabric1211Driver.currentServer();
                if (server == null) {
                    throw new IllegalStateException("probe entity requested before the server was available");
                }
                return net.minecraft.entity.EntityType.PIG.create(server.getOverworld());
            }

            @Override
            public boolean persisted(Object holder) {
                // The attachment rides the holder's own save data under the API's
                // reserved key: presence there is what survives a save/reload —
                // checking the live attachment map would only prove memory.
                net.minecraft.server.MinecraftServer server =
                    dev.vineengine.vine.internal.driver1211.fabric.boot.Fabric1211Driver.currentServer();
                if (server == null) {
                    return false;
                }
                net.minecraft.nbt.NbtCompound tag = new net.minecraft.nbt.NbtCompound();
                try {
                    if (holder instanceof net.minecraft.block.entity.BlockEntity blockEntity) {
                        // createNbt is the public save view (writeNbt is protected).
                        tag = blockEntity.createNbt(server.getRegistryManager());
                    } else if (holder instanceof net.minecraft.entity.Entity entity) {
                        entity.writeNbt(tag);
                    } else {
                        return false;
                    }
                } catch (RuntimeException writeFailure) {
                    return false;
                }
                return tag.contains(net.fabricmc.fabric.api.attachment.v1.AttachmentTarget.NBT_ATTACHMENT_KEY);
            }

            @Override
            public String describe() {
                return "vanilla chest be + pig entity (fabric attachments)";
            }
        };
    }

    /** Registers the component + attachment types; call during mod init. */
    public static void registerComponent() {
        net.minecraft.registry.Registry.register(
            Registries.DATA_COMPONENT_TYPE, Identifier.of("vine", "voxel_data"), VOXEL_DATA);
        LOG.info("[VINE] voxeldata: component vine:voxel_data registered (Fabric)");
        LOG.info("[VINE] voxeldata: attachment vine:voxel_data registered for block entities, entities"
            + " and players (Fabric)");
    }

    /** The attachment holder behind a block entity / entity / player object. */
    private static net.fabricmc.fabric.api.attachment.v1.AttachmentTarget attachmentOf(Object holder) {
        if (holder instanceof net.fabricmc.fabric.api.attachment.v1.AttachmentTarget target) {
            return target;
        }
        throw new UnsupportedOperationException(
            "this cell cannot attach voxel data to " + holder.getClass().getName());
    }

    @Override
    protected byte[] storedBlob(Object holder) {
        if (holder instanceof ItemStack stack) {
            return decode(stack.get(VOXEL_DATA));
        }
        return decode(attachmentOf(holder).getAttached(VOXEL_ATTACHMENT));
    }

    @Override
    protected void storeBlob(Object holder, byte[] blob) {
        if (holder instanceof ItemStack stack) {
            stack.set(VOXEL_DATA, encode(blob));
            return;
        }
        attachmentOf(holder).setAttached(VOXEL_ATTACHMENT, encode(blob));
    }

    @Override
    protected int readNativeInt(Object holder, String componentId) {
        if (!(holder instanceof ItemStack stack) || !VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            // Native interop is an item-stack mapping in this stage; other holder
            // kinds have no component view to read.
            return Integer.MIN_VALUE;
        }
        return stack.getOrDefault(DataComponentTypes.DAMAGE, 0);
    }

    @Override
    protected boolean writeNativeInt(Object holder, String componentId, int value) {
        if (!(holder instanceof ItemStack stack) || !VoxelProbe.NATIVE_DAMAGE.equals(componentId)) {
            return false;
        }
        stack.set(DataComponentTypes.DAMAGE, value);
        return true;
    }
}
