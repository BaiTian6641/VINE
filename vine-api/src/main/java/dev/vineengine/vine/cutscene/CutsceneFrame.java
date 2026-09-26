package dev.vineengine.vine.cutscene;

import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * One tick of a cutscene (sub-23 §2): everything a client needs to draw this instant, and
 * nothing about how. The engine computes it, every viewer receives the same one, and a
 * late joiner receives the frame for the current tick rather than a private timeline.
 *
 * @param tick   which tick this frame describes
 * @param camera where the camera is and what it sees
 * @param actors which actors are performing which clips
 * @param sounds sounds that start on this tick (empty on most ticks)
 * @param titles the text lines visible on this tick
 */
public record CutsceneFrame(long tick, CameraShot camera, List<ActorShot> actors, List<VineId> sounds,
        List<String> titles) {

    /** A camera's state at one tick. */
    public record CameraShot(Vec3 position, float yawDegrees, float pitchDegrees, float fov) {

        public CameraShot {
            Objects.requireNonNull(position, "position");
        }
    }

    /** One actor's performance at one tick. */
    public record ActorShot(VineId actor, String clip, double seconds) {

        public ActorShot {
            Objects.requireNonNull(actor, "actor");
            Objects.requireNonNull(clip, "clip");
        }
    }

    public CutsceneFrame {
        Objects.requireNonNull(camera, "camera");
        actors = List.copyOf(Objects.requireNonNull(actors, "actors"));
        sounds = List.copyOf(Objects.requireNonNull(sounds, "sounds"));
        titles = List.copyOf(Objects.requireNonNull(titles, "titles"));
    }
}
