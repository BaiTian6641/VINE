package dev.vineengine.vine.cutscene;

import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One track of a cutscene (sub-23 §2). Sealed, because a track is a thing the engine knows
 * how to evaluate — a "custom track" would be a client effect with no server-side meaning,
 * and this subsystem's whole claim is that a frame is computed by the server.
 */
public sealed interface Track permits Track.Camera, Track.Actor, Track.Audio, Track.Title {

    /** The first tick this track has anything to say about. */
    long startTick();

    /** The last tick, inclusive. */
    long endTick();

    /** The camera's path: the only track a cutscene must have. */
    record Camera(List<CameraKey> keys) implements Track {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Camera> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            CameraKey.CODEC.listOf().fieldOf("keys").forGetter(Camera::keys)
        ).apply(instance, Camera::new));

        public Camera {
            keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("a camera track needs at least one key");
            }
            long previous = Long.MIN_VALUE;
            for (CameraKey key : keys) {
                if (key.tick() <= previous) {
                    throw new IllegalArgumentException("camera keys must be strictly increasing in tick; "
                        + key.tick() + " follows " + previous);
                }
                previous = key.tick();
            }
        }

        @Override
        public long startTick() {
            return keys.get(0).tick();
        }

        @Override
        public long endTick() {
            return keys.get(keys.size() - 1).tick();
        }
    }

    /**
     * An actor performing a clip: what a cutscene shows when it is not showing the weather.
     *
     * <p>The clip is named by the same string the rest of the engine uses for animation clips
     * ({@code animation.<namespace>.<asset>.<clip>}) rather than a {@code VineId}: clips are
     * named inside an asset, and dressing that up as an id would invent a second way to
     * address the same thing.
     */
    record Actor(VineId actor, String clip, long startTick, long endTick, boolean loop) implements Track {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Actor> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            VineId.CODEC.fieldOf("actor").forGetter(Actor::actor),
            Codec.STRING.fieldOf("animation").forGetter(Actor::clip),
            Codec.LONG.optionalFieldOf("startTick", 0L).forGetter(Actor::startTick),
            Codec.LONG.optionalFieldOf("endTick", Long.MAX_VALUE).forGetter(Actor::endTick),
            Codec.BOOL.optionalFieldOf("loop", true).forGetter(Actor::loop)
        ).apply(instance, Actor::new));

        public Actor {
            Objects.requireNonNull(actor, "actor");
            if (clip == null || clip.isBlank()) {
                throw new IllegalArgumentException("actor track for " + actor + " names no clip");
            }
            if (endTick < startTick) {
                throw new IllegalArgumentException("actor track for " + actor + " ends before it starts ("
                    + startTick + " > " + endTick + ")");
            }
        }
    }

    /** A sound played at one tick. */
    record Audio(VineId sound, long tick, float volume) implements Track {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Audio> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            VineId.CODEC.fieldOf("sound").forGetter(Audio::sound),
            Codec.LONG.fieldOf("tick").forGetter(Audio::tick),
            Codec.FLOAT.optionalFieldOf("volume", 1.0F).forGetter(Audio::volume)
        ).apply(instance, Audio::new));

        public Audio {
            Objects.requireNonNull(sound, "sound");
            if (!(volume >= 0.0F)) {
                throw new IllegalArgumentException("audio track volume must be non-negative, got " + volume);
            }
        }

        @Override
        public long startTick() {
            return tick;
        }

        @Override
        public long endTick() {
            return tick;
        }
    }

    /** A line of text shown between two ticks — a title card or a subtitle. */
    record Title(String text, long startTick, long endTick, VineId style) implements Track {

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final com.mojang.serialization.MapCodec<Title> CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("text").forGetter(Title::text),
            Codec.LONG.optionalFieldOf("startTick", 0L).forGetter(Title::startTick),
            Codec.LONG.fieldOf("endTick").forGetter(Title::endTick),
            VineId.CODEC.optionalFieldOf("style", VineId.of("vine", "subtitle")).forGetter(Title::style)
        ).apply(instance, Title::new));

        public Title {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(style, "style");
            if (text.isBlank()) {
                throw new IllegalArgumentException("title track text must not be blank");
            }
            if (endTick < startTick) {
                throw new IllegalArgumentException("title track '" + text + "' ends before it starts (" + startTick
                    + " > " + endTick + ")");
            }
        }
    }

    /** Single source of truth for every representation of this data (sub-02 §2). */
    Codec<Track> CODEC = Codec.STRING.dispatch("type", Track::typeName, Track::codecFor);

    /** The {@code "type"} discriminator an authored track writes. */
    default String typeName() {
        return getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
    }

    private static com.mojang.serialization.MapCodec<? extends Track> codecFor(String typeName) {
        return switch (typeName) {
            case "camera" -> Camera.CODEC;
            case "actor" -> Actor.CODEC;
            case "audio" -> Audio.CODEC;
            case "title" -> Title.CODEC;
            default -> throw new IllegalArgumentException("unknown track type '" + typeName
                + "' — expected camera, actor, audio or title");
        };
    }
}
