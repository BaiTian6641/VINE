package dev.vineengine.vine.internal.driver1211.fabric.net;

import java.util.List;
import java.util.Objects;

import dev.vineengine.vine.cutscene.CutsceneFrame;
import dev.vineengine.vine.net.CodecException;
import dev.vineengine.vine.net.PayloadCodec;
import dev.vineengine.vine.net.VineBuf;
import dev.vineengine.vine.net.VineCodecs;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The wire form of one cutscene frame (sub-23's delivery half, v1) — this cell's own
 * payload, the way {@code vine:part_delta}'s envelope is this cell's.
 *
 * <p><b>Why the bytes are the cell's.</b> The engine's {@link CutsceneFrame} is engine types
 * with no channel descriptor: the engine evaluates a frame and hands it to a seam, and moving
 * it is the driver's job. So the layout lives here, beside the transport that uses it, and the
 * NeoForge cell builds the same one (its own copy, exactly as the part delta's envelope is
 * duplicated per cell — no shared source set, so a cell can never be broken by the other's
 * refactor).
 *
 * <p><b>Layout, version 1</b> — engine {@link VineBuf} scalars throughout, so the engine's own
 * bounds checks (hostile length prefixes throw {@link CodecException}, never allocate) apply on
 * the receiving side:
 * <pre>
 * varInt   version (1)
 * boolean  ended — true is the end marker, and nothing follows it
 * id       cutscene (canonical "namespace:path")
 * long     tick
 * long,long,long  camera x, y, z (raw IEEE-754 bits — the engine's exact doubles)
 * varInt,varInt,varInt  camera yaw, pitch, fov (raw float bits — again no rounding)
 * list&lt;id&gt;  sounds  (≤ 64)
 * list&lt;utf&gt; titles (≤ 64)
 * </pre>
 *
 * <p><b>What the wire does not carry.</b> An audio track's authored volume: the engine's
 * {@code Track.Audio} holds one, but {@code CutsceneFrame.sounds()} is a list of ids and the
 * frame is the whole contract, so the volume stops at the seam (engine gap, reported). Actor
 * shots are deliberately absent: the cell has no engine entity model to apply them to, so the
 * frame's {@code actors()} never crosses (documented gap, sub-23 v1).
 *
 * <p><b>Version, not negotiation.</b> A decoder refuses a version it does not know rather than
 * guessing at a layout: the two sides of a closed cell/engine pair ship together, so a
 * mismatch is a build error that has to be loud.
 *
 * <p><b>Size, measured rather than assumed.</b> A frame is ~76 bytes with no title or sound
 * (~100 with the one title line the exemplar shows), so a cinematic costs about 1.5–2 KB/s per
 * viewer at 20 ticks — a rare, explicitly-addressed payload next to the part delta's sustained
 * per-entity budget. The engine's exact values ride as raw double/float bits instead of being
 * quantized to save bytes: a sound is placed with the camera position, and the frame is what any
 * later camera application would read, so fidelity is worth more here than 25 bytes.
 */
public final class CutsceneFramePayload {

    /** Bumped when the layout below changes; the decoder refuses anything else. */
    public static final int VERSION = 1;

    /** Bound on each frame's sound and title lists — a frame with more is an authoring bug. */
    private static final int MAX_TRACKS_PER_FRAME = 64;

    private static final PayloadCodec<List<VineId>> SOUNDS = VineCodecs.list(VineCodecs.ID, MAX_TRACKS_PER_FRAME);
    private static final PayloadCodec<List<String>> TITLES = VineCodecs.list(VineCodecs.UTF, MAX_TRACKS_PER_FRAME);

    /** The end marker singleton — {@code ended} frames carry no moment of their own. */
    private static final Decoded END = new Decoded(true, null, 0L, Vec3.ZERO, 0.0F, 0.0F, 0.0F, List.of(), List.of());

    /** One decoded frame, or the end of a cutscene. */
    public record Decoded(boolean ended, VineId cutscene, long tick, Vec3 cameraPosition, float yawDegrees,
            float pitchDegrees, float fov, List<VineId> sounds, List<String> titles) {

        /**
         * The end of a cutscene: {@code cutscene} is {@code null} and every other field is a
         * zero value, because an ending is not a moment. Read {@link #ended()} first.
         */
        public static Decoded end() {
            return END;
        }

        public Decoded {
            if (!ended) {
                Objects.requireNonNull(cutscene, "cutscene");
                Objects.requireNonNull(cameraPosition, "cameraPosition");
                sounds = List.copyOf(Objects.requireNonNull(sounds, "sounds"));
                titles = List.copyOf(Objects.requireNonNull(titles, "titles"));
            }
        }
    }

    private CutsceneFramePayload() {
    }

    /** Encodes one frame of {@code cutscene} as it should be drawn this tick. */
    public static byte[] encode(VineId cutscene, CutsceneFrame frame) {
        VineBuf buf = VineCodecs.buffer();
        buf.writeVarInt(VERSION);
        buf.writeBoolean(false);
        buf.writeId(cutscene);
        buf.writeLong(frame.tick());
        CutsceneFrame.CameraShot camera = frame.camera();
        buf.writeLong(Double.doubleToRawLongBits(camera.position().x()));
        buf.writeLong(Double.doubleToRawLongBits(camera.position().y()));
        buf.writeLong(Double.doubleToRawLongBits(camera.position().z()));
        buf.writeVarInt(Float.floatToRawIntBits(camera.yawDegrees()));
        buf.writeVarInt(Float.floatToRawIntBits(camera.pitchDegrees()));
        buf.writeVarInt(Float.floatToRawIntBits(camera.fov()));
        SOUNDS.encode(buf, frame.sounds());
        TITLES.encode(buf, frame.titles());
        return buf.toByteArray();
    }

    /** Encodes the end marker: the cutscene stopped producing frames. */
    public static byte[] encodeEnd() {
        VineBuf buf = VineCodecs.buffer();
        buf.writeVarInt(VERSION);
        buf.writeBoolean(true);
        return buf.toByteArray();
    }

    /**
     * Decodes a payload written by {@link #encode} or {@link #encodeEnd}.
     *
     * @throws CodecException on an unknown version, a truncated payload, or a trailing byte —
     *     a corrupt frame is refused rather than half-applied
     */
    public static Decoded decode(byte[] payload) {
        VineBuf buf = VineCodecs.readBuffer(payload);
        int version = buf.readVarInt();
        if (version != VERSION) {
            throw new CodecException("cutscene frame version " + version + " is not version " + VERSION);
        }
        if (buf.readBoolean()) {
            return END;
        }
        VineId cutscene = buf.readId();
        long tick = buf.readLong();
        Vec3 position = Vec3.of(Double.longBitsToDouble(buf.readLong()), Double.longBitsToDouble(buf.readLong()),
            Double.longBitsToDouble(buf.readLong()));
        float yaw = Float.intBitsToFloat(buf.readVarInt());
        float pitch = Float.intBitsToFloat(buf.readVarInt());
        float fov = Float.intBitsToFloat(buf.readVarInt());
        List<VineId> sounds = SOUNDS.decode(buf);
        List<String> titles = TITLES.decode(buf);
        if (buf.readableBytes() != 0) {
            throw new CodecException("cutscene frame has " + buf.readableBytes() + " trailing byte(s)");
        }
        return new Decoded(false, cutscene, tick, position, yaw, pitch, fov, sounds, titles);
    }
}
