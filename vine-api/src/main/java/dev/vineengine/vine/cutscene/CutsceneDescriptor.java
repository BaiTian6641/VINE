package dev.vineengine.vine.cutscene;

import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * A cutscene as data (sub-23 §2): a length, a set of tracks, and whether a player may skip
 * it. The camera track is mandatory — a cinematic with nothing to look through is a
 * script, and the engine will not guess a camera.
 *
 * @param id          the cutscene's engine id
 * @param lengthTicks how long it runs, in server ticks
 * @param tracks      its tracks, in declaration order
 * @param skippable   whether a viewer may end it early
 */
public record CutsceneDescriptor(VineId id, int lengthTicks, List<Track> tracks, boolean skippable) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<CutsceneDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(CutsceneDescriptor::id),
        Codec.INT.fieldOf("lengthTicks").forGetter(CutsceneDescriptor::lengthTicks),
        Track.CODEC.listOf().fieldOf("tracks").forGetter(CutsceneDescriptor::tracks),
        Codec.BOOL.optionalFieldOf("skippable", true).forGetter(CutsceneDescriptor::skippable)
    ).apply(instance, CutsceneDescriptor::new));

    public CutsceneDescriptor {
        Objects.requireNonNull(id, "id");
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (lengthTicks <= 0) {
            throw new IllegalArgumentException("CutsceneDescriptor " + id + ": lengthTicks must be positive, got "
                + lengthTicks);
        }
        boolean camera = false;
        for (Track track : tracks) {
            Objects.requireNonNull(track, "tracks element");
            camera |= track instanceof Track.Camera;
            if (track.startTick() >= lengthTicks) {
                throw new IllegalArgumentException("CutsceneDescriptor " + id + ": a " + track.typeName()
                    + " track starts at " + track.startTick() + ", at or past the cutscene's length of "
                    + lengthTicks + " ticks — it would never play");
            }
        }
        if (!camera) {
            throw new IllegalArgumentException("CutsceneDescriptor " + id + " has no camera track — a cutscene"
                + " without a camera is a script, and the engine will not invent one");
        }
    }
}
