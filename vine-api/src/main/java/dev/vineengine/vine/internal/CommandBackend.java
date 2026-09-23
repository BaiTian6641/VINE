package dev.vineengine.vine.internal;

import dev.vineengine.vine.command.VineCommands;

/**
 * Internal bridge from {@code VineCommands} (vine-api) to vine-core's command
 * service. NOT public API — implemented once by vine-core's engine object;
 * never implemented or referenced by consumers.
 *
 * <p>Resolved through {@link EngineAccess} rather than its own ServiceLoader
 * seam, so the engine's exactly-one-provider rule, boot ordering, and cached
 * boot failure apply to command calls unchanged (same pattern as
 * {@link RegistryBackend}).
 */
public interface CommandBackend {

    /** See {@code VineCommands#get()}. */
    VineCommands commands();
}
