package dev.vineengine.vine.animation;

import java.util.List;
import java.util.Map;

import dev.vineengine.vine.world.Vec3;

/**
 * A parsed animation asset (sub-09 §2): the skeleton and the clips, in the engine's
 * own shape rather than the authoring format's.
 *
 * <p>Why a parsed shape exists at all: the asset format is Blockbench/GeckoLib data
 * (bones with parents and pivots, clips whose channels are keyed by <em>string</em>
 * times), and both VINE consumers — the server-side evaluator and, later, the client
 * render backend — would otherwise re-derive the same structure from JSON. The parser
 * is the one place the format is read; everything downstream speaks this.
 *
 * <p>This is data: immutable, no behaviour, no client type. The evaluation itself is
 * {@link VineAnimations}, which is engine-side and headless by contract.
 */
public record AnimationAsset(String name, Map<String, Bone> bones, Map<String, Clip> clips) {

    public AnimationAsset {
        bones = Map.copyOf(bones);
        clips = Map.copyOf(clips);
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("AnimationAsset.name must name the asset");
        }
    }

    /** One bone of the skeleton: its parent, its rest pivot and its rest rotation in degrees. */
    public record Bone(String name, String parent, Vec3 pivot, Vec3 rotation) {

        public Bone {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Bone.name must not be blank");
            }
        }

        /** Whether this bone is a root (no parent). */
        public boolean isRoot() {
            return parent == null || parent.isBlank();
        }
    }

    /** One animation clip: its length, its loop flag, its channels and its markers. */
    public record Clip(String name, double lengthSeconds, boolean loop, Map<String, Channel> channels,
            List<Marker> markers) {

        public Clip {
            channels = Map.copyOf(channels);
            markers = List.copyOf(markers);
        }

        /** The channels of one bone. */
        public Channel channel(String bone) {
            return channels.get(bone);
        }
    }

    /** A bone's animated channels inside one clip; an absent channel is the bone's rest value. */
    public record Channel(List<Keyframe> position, List<Keyframe> rotation, List<Keyframe> scale) {

        public Channel {
            position = position == null ? List.of() : List.copyOf(position);
            rotation = rotation == null ? List.of() : List.copyOf(rotation);
            scale = scale == null ? List.of() : List.copyOf(scale);
        }
    }

    /** One keyframe: a time in seconds and the value at it, interpolated with {@code easing}. */
    public record Keyframe(double timeSeconds, Vec3 value, Easing easing) {

        public Keyframe {
            if (easing == null) {
                easing = Easing.LINEAR;
            }
        }
    }

    /**
     * One marker inside a clip: a named instant the engine reads as a gameplay event.
     * VINE namespaces its own marker names ({@code vine:…}), so an author's sound or
     * particle markers can coexist with the ones that drive combat timing.
     */
    public record Marker(double timeSeconds, String name) {

        public Marker {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Marker.name must not be blank");
            }
        }
    }

    /** The easing curves the format defines; unknown names parse as {@link #LINEAR}. */
    public enum Easing {

        /** Straight interpolation between the two neighbours. */
        LINEAR,

        /** Catmull-Rom spline through the neighbours (the format's default for smooth motion). */
        CATMULLROM,

        /** Hold the previous value until the next keyframe. */
        STEP,

        /** Ease into the next value. */
        EASE_IN,

        /** Ease out of the previous value. */
        EASE_OUT,

        /** Ease at both ends. */
        EASE_IN_OUT
    }
}
