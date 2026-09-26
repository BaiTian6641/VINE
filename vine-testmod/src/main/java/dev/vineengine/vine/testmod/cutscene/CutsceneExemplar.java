package dev.vineengine.vine.testmod.cutscene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import dev.vineengine.vine.cutscene.CutsceneFrame;
import dev.vineengine.vine.cutscene.VineCutscenes;
import dev.vineengine.vine.registry.VineId;

/**
 * The sub-23 Stage B exemplar: play the authored cutscene for two viewers, advance it by
 * server ticks, and check that both viewers were handed the same frames — which is the whole
 * claim of a server-driven cinematic.
 *
 * <p>Frames are captured through the runtime's own transport seam, so the scenario asserts
 * what a client would actually have received rather than what the engine says it would send.
 */
public final class CutsceneExemplar {

    /** The JSON-authored cutscene (data/vine_test/vine/cutscene/wyvern_strike.json). */
    private static final VineId CUTSCENE = VineId.of("vine_test", "wyvern_strike");

    /** Two viewers; who they are is irrelevant to the engine, which addresses players by id. */
    private static final UUID VIEWER_A = UUID.nameUUIDFromBytes("vine:tck:viewer-a".getBytes());
    private static final UUID VIEWER_B = UUID.nameUUIDFromBytes("vine:tck:viewer-b".getBytes());

    private static final Map<UUID, List<CutsceneFrame>> RECEIVED = new HashMap<>();
    private static boolean senderInstalled;

    private CutsceneExemplar() {
    }

    /**
     * Plays the cutscene for two viewers and reports the frame two ticks in.
     *
     * <p><b>Who watches.</b> The two fixed viewers are the scenario's own subjects — they exist
     * so a clientless run can compare two frame streams, and the headless scenario counts them
     * ({@code viewers=2}). {@code caller} is the player who ran the command, when one did: a
     * real client can only be shown a cutscene it is *addressed* in, so without it the command
     * would play a cinematic no client ever receives. A console source passes {@code null} and
     * the viewer set is exactly the two fixed ones.
     */
    public static void play(UUID caller) {
        installSenderOnce();
        RECEIVED.clear();
        Set<UUID> viewers = new java.util.LinkedHashSet<>();
        viewers.add(VIEWER_A);
        viewers.add(VIEWER_B);
        if (caller != null) {
            viewers.add(caller);
        }
        VineCutscenes.play(CUTSCENE, viewers);
        System.out.println("tck: cutscene playing=" + VineCutscenes.playing()
            + " id=" + VineCutscenes.current().orElse(null)
            + " viewers=" + VineCutscenes.viewers().size());
        VineCutscenes.tick();
        VineCutscenes.tick();
        CutsceneFrame frame = VineCutscenes.frame().orElseThrow();
        System.out.println("tck: cutscene frameAfter2 tick=" + frame.tick()
            + " camera=" + frame.camera().position().asString()
            + " produced=" + RECEIVED.values().stream().mapToInt(List::size).sum());
    }

    /** Runs the cutscene to its end and reports how both viewers' frame streams compare. */
    public static void finish() {
        for (int i = 0; i < 60; i++) {
            VineCutscenes.tick();
        }
        List<CutsceneFrame> a = RECEIVED.getOrDefault(VIEWER_A, List.of());
        List<CutsceneFrame> b = RECEIVED.getOrDefault(VIEWER_B, List.of());
        boolean identical = a.size() == b.size();
        for (int i = 0; identical && i < a.size(); i++) {
            identical = a.get(i).equals(b.get(i));
        }
        System.out.println("tck: cutscene ended playing=" + VineCutscenes.playing()
            + " framesA=" + a.size() + " framesB=" + b.size()
            + " identical=" + identical
            + " lastTick=" + (a.isEmpty() ? -1 : a.get(a.size() - 1).tick()));
    }

    /** Captures what the transport would have sent, per viewer. */
    private static void installSenderOnce() {
        if (senderInstalled) {
            return;
        }
        senderInstalled = true;
        VineCutscenes.addFrameListener((viewer, frame) ->
            RECEIVED.computeIfAbsent(viewer, key -> new ArrayList<>()).add(frame));
    }
}
