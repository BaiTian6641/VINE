package dev.vineengine.vine.command;

import dev.vineengine.vine.internal.CommandBackend;
import dev.vineengine.vine.internal.EngineAccess;

/**
 * The engine command-registration surface (sub-06 §2). Consumers describe
 * commands as data via the {@link VineCommand} builder and register the
 * descriptor here; drivers translate the descriptors into each loader's
 * native command-registration hook near-mechanically.
 *
 * <p>Registration closes when the engine enters {@code REGISTRIES_FROZEN}
 * (sub-01) — the first native dispatcher build happens after the freeze, so a
 * late registration could never reach a dispatcher and is rejected explicitly
 * instead of silently never firing.
 *
 * <p>Backed by vine-core through the same {@code ServiceLoader} boot seam as
 * {@code VineEngine.get()}, so the exactly-one-provider rule and cached boot
 * failure apply unchanged. Dormant with zero consumers (Minimal Footprint):
 * no descriptor registered means no driver-side command attached.
 */
public interface VineCommands {

    /**
     * Returns the command service, booting the engine on first call (same seam
     * and failure semantics as {@code VineEngine.get()}).
     */
    static VineCommands get() {
        if (EngineAccess.get() instanceof CommandBackend backend) {
            return backend.commands();
        }
        throw new IllegalStateException(
            "vine-core engine does not provide command services — mismatched vine-api/vine-core jars");
    }

    /**
     * Registers one command descriptor.
     *
     * @throws IllegalArgumentException if the descriptor fails engine
     *         compilation (structural or unsupported-for-this-stage rules)
     * @throws IllegalStateException if the descriptor id is already registered,
     *         or registration has closed ({@code REGISTRIES_FROZEN} entered)
     */
    void register(CommandDescriptor descriptor);
}
