package dev.vineengine.vine.cutscene;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import dev.vineengine.vine.internal.CutsceneBackend;
import dev.vineengine.vine.internal.EngineAccess;
import dev.vineengine.vine.registry.VineId;

/**
 * Static entry point for cutscenes (sub-23 §2). Mirror of the other facades: content is
 * authored as data, the server owns the clock, and a client only ever applies a frame it
 * was sent.
 *
 * <p><b>Why the server owns a cinematic.</b> A cutscene fired by the client is a cutscene
 * every other player misses, and a cutscene whose clock is local drifts between viewers.
 * Here the server evaluates one frame per tick and hands the same frame to every viewer, so
 * two players watching the same moment really are watching the same moment.
 */
public final class VineCutscenes {

    private VineCutscenes() {
    }

    /**
     * Starts {@code cutscene} for {@code viewers}. A cutscene already playing is replaced —
     * one cinematic at a time is a v1 decision, stated rather than left ambiguous.
     */
    public static void play(VineId cutscene, Set<UUID> viewers) {
        backend().play(Objects.requireNonNull(cutscene, "cutscene"), Set.copyOf(Objects.requireNonNull(viewers,
            "viewers")));
    }

    /** Stops whatever is playing, for everyone. */
    public static void stop() {
        backend().stop();
    }

    /** Whether a cutscene is playing. */
    public static boolean playing() {
        return backend().playing();
    }

    /** The cutscene playing, if any. */
    public static Optional<VineId> current() {
        return backend().current();
    }

    /** Who is watching. */
    public static Set<UUID> viewers() {
        return backend().viewers();
    }

    /**
     * Registers a listener for every frame sent to every viewer — how a consumer hangs an
     * effect off a cinematic (a rumble, a quest step, a score) without a client of its own.
     * Delivery order is registration order, and listeners run on the server tick that
     * produced the frame.
     */
    public static void addFrameListener(java.util.function.BiConsumer<UUID, CutsceneFrame> listener) {
        backend().addFrameListener(Objects.requireNonNull(listener, "listener"));
    }

    /** Advances the cutscene one server tick. */
    public static void tick() {
        backend().tickCutscenes();
    }

    /** The frame for the current tick, or empty when nothing is playing (or it has ended). */
    public static Optional<CutsceneFrame> frame() {
        return backend().frame();
    }

    /**
     * Evaluates {@code cutscene} at {@code tick} without playing it — what a golden fixture
     * uses, and what an authoring tool would preview with.
     */
    public static CutsceneFrame evaluate(VineId cutscene, long tick) {
        return backend().evaluate(Objects.requireNonNull(cutscene, "cutscene"), tick);
    }

    private static CutsceneBackend backend() {
        if (EngineAccess.get() instanceof CutsceneBackend backend) {
            return backend;
        }
        throw new IllegalStateException(
            "the running engine does not provide cutscenes (headless runtime, or vine-core is not the engine)");
    }
}
