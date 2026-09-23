package dev.vineengine.vine.internal.spi;

import dev.vineengine.vine.capability.CapabilityScope;
import dev.vineengine.vine.capability.CapabilityTarget;
import dev.vineengine.vine.registry.VineId;

/**
 * The driver-side capability interop SPI (sub-04 §2 "Driver contract"): the
 * per-cell wiring that lets foreign mods see engine capabilities and lets the
 * engine query native ones. One per cell, implemented only by VINE's own
 * driver jars; the engine's fallback store works without any implementation
 * (it <em>is</em> the portability floor), so this SPI is best-effort interop,
 * never a requirement.
 *
 * <p><b>Layering rule:</b> a native interop result is wrapped in the engine
 * type at the driver boundary — consumer code never sees a native type, and
 * partner absence degrades through feature probes, never exceptions. The
 * engine never hands capability NBT to drivers; state persists through
 * sub-03's storage driver at the target's save boundary.
 */
public interface CapabilityDriver {

    /**
     * Exposes an engine capability instance to native queries for one scope's
     * targets: NeoForge cells register a provider in
     * {@code RegisterCapabilitiesEvent} that delegates into the engine query;
     * Fabric cells register fallback returns on
     * {@code Block/Entity/ItemApiLookup}.
     */
    <T> void exposeNative(CapabilityScope scope, VineId typeId, Class<T> apiClass);

    /**
     * Queries a foreign/native capability, wrapped in the engine type — or
     * {@code null} when the partner, loader path, or target cannot serve it.
     * Never throws.
     */
    Object queryNative(VineId nativeId, CapabilityTarget target);
}
