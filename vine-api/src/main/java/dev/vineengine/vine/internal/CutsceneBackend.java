package dev.vineengine.vine.internal;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.vineengine.vine.cutscene.CutsceneFrame;
import dev.vineengine.vine.registry.VineId;

/**
 * Internal bridge from {@code VineCutscenes} (vine-api) to vine-core's cutscene runtime.
 * NOT public API — implemented once by vine-core's engine object; never implemented or
 * referenced by consumers.
 */
public interface CutsceneBackend {

    /** See {@code VineCutscenes#play}. */
    void play(VineId cutscene, Set<UUID> viewers);

    /** See {@code VineCutscenes#stop}. */
    void stop();

    /** See {@code VineCutscenes#playing}. */
    boolean playing();

    /** See {@code VineCutscenes#current}. */
    Optional<VineId> current();

    /** See {@code VineCutscenes#viewers}. */
    Set<UUID> viewers();

    /**
     * See {@code VineCutscenes#tick}. Named for its subsystem rather than sharing the engine's
     * single {@code tick()}: a quest flush and a cinematic frame are different work, and an
     * interface that called both "tick" would make it impossible to advance one without the
     * other.
     */
    void tickCutscenes();

    /** See {@code VineCutscenes#frame}. */
    Optional<CutsceneFrame> frame();

    /** See {@code VineCutscenes#evaluate}. */
    CutsceneFrame evaluate(VineId cutscene, long tick);

    /** See {@code VineCutscenes#addFrameListener}. */
    void addFrameListener(java.util.function.BiConsumer<UUID, CutsceneFrame> listener);
}
