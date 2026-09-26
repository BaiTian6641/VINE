package dev.vineengine.vine.cutscene;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.world.Vec3;

/**
 * One camera key: where the camera is, which way it looks and how wide, at one tick
 * (sub-23 §2).
 *
 * <p>Interpolation is per segment and named by the <em>earlier</em> key: the easing on a
 * key describes the segment that leaves it, which is how an author writes "cut here, then
 * glide" without a second list of segments to keep in sync.
 *
 * @param tick     when the camera is exactly here
 * @param position the camera's position
 * @param yawDegrees yaw, in the same convention the rest of the engine uses for actors
 * @param pitchDegrees pitch, positive looking down (the convention a player's own pitch uses)
 * @param fov      vertical field of view in degrees
 * @param easing   how the segment leaving this key interpolates
 */
public record CameraKey(long tick, Vec3 position, float yawDegrees, float pitchDegrees, float fov, Easing easing) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<CameraKey> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.LONG.fieldOf("tick").forGetter(CameraKey::tick),
        Vec3.CODEC.fieldOf("position").forGetter(CameraKey::position),
        Codec.FLOAT.optionalFieldOf("yaw", 0.0F).forGetter(CameraKey::yawDegrees),
        Codec.FLOAT.optionalFieldOf("pitch", 0.0F).forGetter(CameraKey::pitchDegrees),
        Codec.FLOAT.optionalFieldOf("fov", 70.0F).forGetter(CameraKey::fov),
        Easing.CODEC.optionalFieldOf("easing", Easing.LINEAR).forGetter(CameraKey::easing)
    ).apply(instance, CameraKey::new));

    public CameraKey {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(easing, "easing");
        if (tick < 0L) {
            throw new IllegalArgumentException("CameraKey: tick must be non-negative, got " + tick);
        }
        if (!(fov > 0.0F) || fov >= 180.0F) {
            throw new IllegalArgumentException("CameraKey: fov must be in (0, 180), got " + fov);
        }
    }

    /** How a segment between two keys is shaped. */
    public enum Easing {

        /** Constant speed. */
        LINEAR,

        /** Slow at both ends. */
        SMOOTH,

        /** Instant: the camera is at this key from its tick onward, with no glide. */
        CUT;

        /** Single source of truth for every representation of this data (sub-02 §2). */
        public static final Codec<Easing> CODEC = Codec.STRING.xmap(
            name -> Easing.valueOf(name.toUpperCase(java.util.Locale.ROOT)),
            easing -> easing.name().toLowerCase(java.util.Locale.ROOT));
    }
}
