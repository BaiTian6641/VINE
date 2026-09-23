package dev.vineengine.vine.hook;

import java.util.Objects;

import dev.vineengine.vine.Cancellable;
import dev.vineengine.vine.VineEvent;

/**
 * M0 hook-family event records (sub-01 Stage C, prime-invariant payloads §5.9):
 * JDK types only, identical shape on every loader, so consumers never observe a
 * loader axis. Ids are {@code namespace:path} strings; an empty
 * {@code playerUuid} means "no player involved".
 *
 * <p>Vetoable events are classes, not records: {@link Cancellable} carries
 * per-event mutable state, which records cannot hold.
 */
public final class HookEvents {

    private HookEvents() {
    }

    /** A block was placed in a world (vetoable pre-event). */
    public static final class BlockPlace implements VineEvent, Cancellable {
        private final String worldId;
        private final String blockId;
        private final int x;
        private final int y;
        private final int z;
        private final String playerUuid;
        private boolean cancelled;

        public BlockPlace(String worldId, String blockId, int x, int y, int z, String playerUuid) {
            this.worldId = Objects.requireNonNull(worldId, "worldId");
            this.blockId = Objects.requireNonNull(blockId, "blockId");
            this.x = x;
            this.y = y;
            this.z = z;
            this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        }

        public String worldId() {
            return worldId;
        }

        public String blockId() {
            return blockId;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public String playerUuid() {
            return playerUuid;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** A block was broken (vetoable: cancelling suppresses engine reactions). */
    public static final class BlockBreak implements VineEvent, Cancellable {
        private final String worldId;
        private final String blockId;
        private final int x;
        private final int y;
        private final int z;
        private final String playerUuid;
        private boolean cancelled;

        public BlockBreak(String worldId, String blockId, int x, int y, int z, String playerUuid) {
            this.worldId = Objects.requireNonNull(worldId, "worldId");
            this.blockId = Objects.requireNonNull(blockId, "blockId");
            this.x = x;
            this.y = y;
            this.z = z;
            this.playerUuid = Objects.requireNonNull(playerUuid, "playerUuid");
        }

        public String worldId() {
            return worldId;
        }

        public String blockId() {
            return blockId;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        public String playerUuid() {
            return playerUuid;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** A world finished loading. */
    public record WorldLoad(String worldId) implements VineEvent {
    }

    /** A world is being saved. */
    public record WorldSave(String worldId) implements VineEvent {
    }

    /** A structural descriptor was materialized into a vanilla registry. */
    public record RegistryRegister(String registryId, String entryId) implements VineEvent {
    }

    /** An engine payload arrived on the inbound path. */
    public record PacketReceive(String channelId, String playerUuid, byte[] payload) implements VineEvent {
    }

    /** An engine command is about to run (vetoable: cancelling suppresses execution). */
    public static final class CommandExecute implements VineEvent, Cancellable {
        private final String input;
        private final String senderName;
        private boolean cancelled;

        public CommandExecute(String input, String senderName) {
            this.input = Objects.requireNonNull(input, "input");
            this.senderName = Objects.requireNonNull(senderName, "senderName");
        }

        public String input() {
            return input;
        }

        public String senderName() {
            return senderName;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
