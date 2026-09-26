package dev.vineengine.vine.internal.core;

import java.util.Objects;

import dev.vineengine.vine.EnginePhase;
import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.hook.HookSlot;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.internal.registry.DescriptorStore;
import dev.vineengine.vine.internal.registry.IdMapStore;
import dev.vineengine.vine.internal.session.SessionService;
import dev.vineengine.vine.internal.spi.WorldStoreSpi;
import dev.vineengine.vine.internal.spi.DesignRegistryView;
import dev.vineengine.vine.internal.spi.StructuralRegistryView;
import dev.vineengine.vine.internal.spi.VineDriver;

/**
 * vine-core's half of the driver handshake. Valid for the process lifetime: loader
 * lifecycle events advance phases (and read the structural view) long after
 * {@code bootstrap} returns.
 */
final class CoreDriverContext implements VineDriver.DriverContext {

    private final PhaseMachine machine;
    private final DescriptorStore registries;
    private final EngineEventBus bus;
    private final SessionService sessions;
    private final IdMapStore idMap;
    private final dev.vineengine.vine.internal.quest.QuestService quests;
    private volatile dev.vineengine.vine.internal.spi.WorldStoreSpi mountedStore;

    CoreDriverContext(PhaseMachine machine, DescriptorStore registries, EngineEventBus bus,
            SessionService sessions, IdMapStore idMap, dev.vineengine.vine.internal.quest.QuestService quests) {
        this.machine = machine;
        this.registries = registries;
        this.bus = bus;
        this.sessions = sessions;
        this.idMap = idMap;
        this.quests = quests;
    }

    @Override
    public void advancePhase(EnginePhase next) {
        machine.advanceTo(Objects.requireNonNull(next, "next"));
    }

    @Override
    public StructuralRegistryView structuralRegistries() {
        return registries.structuralView();
    }

    @Override
    public EventBus bus() {
        return bus;
    }

    @Override
    public void installHook(HookSlot slot, Runnable install, Runnable uninstall) {
        bus.attachSlot(Objects.requireNonNull(slot, "slot"),
            Objects.requireNonNull(install, "install"),
            Objects.requireNonNull(uninstall, "uninstall"));
    }

    @Override
    public void mountWorldStore(WorldStoreSpi spi) {
        WorldStoreSpi store = Objects.requireNonNull(spi, "spi");
        mountedStore = store;
        idMap.mount(store);
        // Complete the map in key order (sub-02 Stage D): a world's numbering must
        // not depend on read order, registration order, or which cell runs it.
        idMap.assignMissing(registries.structuralKeys());
        sessions.mount(store);
        quests.mount(store);
    }

    @Override
    public void flushWorldStore() {
        idMap.flush();
        sessions.flush();
        if (mountedStore != null) {
            quests.flush(mountedStore);
        }
    }

    @Override
    public DesignRegistryView designRegistries() {
        return registries.designView();
    }

    @Override
    public void reportDesignEntries(VineId registryId, java.util.Map<VineId, Object> entries) {
        java.util.Objects.requireNonNull(registryId, "registryId");
        java.util.Objects.requireNonNull(entries, "entries");
        registries.putDesignEntries(registryId, entries);
        // One materialization event per entry: the same REGISTRY_REGISTER hook
        // consumers already use for structural descriptors (sub-01 Stage C).
        for (VineId entryId : entries.keySet()) {
            bus.post(new dev.vineengine.vine.hook.HookEvents.RegistryRegister(
                registryId.toString(), entryId.toString()));
        }
    }
}
