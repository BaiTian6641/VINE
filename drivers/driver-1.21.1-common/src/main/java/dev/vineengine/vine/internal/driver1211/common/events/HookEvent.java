package dev.vineengine.vine.internal.driver1211.common.events;

/**
 * Normalized, version-free hook payloads (Prime Invariant): JDK/engine types only,
 * identical shape on both loaders, so consumers never observe a loader axis leak.
 * Ids are {@code "namespace:path"} strings until sub-02's {@code VineId} can cross
 * the driver boundary; empty {@code playerUuid} means "no player involved".
 *
 * <p>These records are the driver-internal stand-in for sub-01's Stage C hook
 * events: when vine-api ships the official family records, the installers keep
 * translating the same native sources and this type is replaced — the pending
 * seam is confined to this file.
 */
public sealed interface HookEvent {

    /** Which hook produced this event. */
    VineHook hook();

    record BlockPlace(String worldId, String blockId, int x, int y, int z, String playerUuid) implements HookEvent {
        @Override
        public VineHook hook() {
            return VineHook.BLOCK_PLACE;
        }
    }

    record BlockBreak(String worldId, String blockId, int x, int y, int z, String playerUuid) implements HookEvent {
        @Override
        public VineHook hook() {
            return VineHook.BLOCK_BREAK;
        }
    }

    record WorldLoad(String worldId) implements HookEvent {
        @Override
        public VineHook hook() {
            return VineHook.WORLD_LOAD;
        }
    }

    record WorldSave(String worldId) implements HookEvent {
        @Override
        public VineHook hook() {
            return VineHook.WORLD_SAVE;
        }
    }

    record RegistryRegister(String registryId, String entryId) implements HookEvent {
        @Override
        public VineHook hook() {
            return VineHook.REGISTRY_REGISTER;
        }
    }

    record PacketReceive(String channelId, String playerUuid, byte[] payload) implements HookEvent {
        @Override
        public VineHook hook() {
            return VineHook.PACKET_RECEIVE;
        }
    }

    /** {@code input} is the raw command line as typed (without leading slash). */
    record CommandExecute(String input, String senderName) implements HookEvent {
        @Override
        public VineHook hook() {
            return VineHook.COMMAND_EXECUTE;
        }
    }
}
