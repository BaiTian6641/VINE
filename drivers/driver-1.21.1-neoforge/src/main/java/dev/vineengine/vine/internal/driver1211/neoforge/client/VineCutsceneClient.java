package dev.vineengine.vine.internal.driver1211.neoforge.client;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.vineengine.vine.cutscene.CutsceneFrame;
import dev.vineengine.vine.internal.driver1211.neoforge.net.CutsceneFramePayload;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The 1.21.1 NeoForge client half of sub-23's cutscene delivery — the Fabric cell's
 * {@code VineCutsceneClient} on this loader: it receives the frames the server addresses to
 * this player and applies the ones vanilla already supports — camera yaw/pitch, titles,
 * sounds. Everything here is a client-dist path: it is wired from {@link
 * dev.vineengine.vine.internal.driver1211.neoforge.VineNeoForgeClient}, so a dedicated server
 * never loads this class.
 *
 * <p><b>What a frame is applied as (v1, no Mixin — the sub-23 decision).</b>
 * <ul>
 *   <li><b>Camera:</b> the local player's yaw and pitch are set to the frame's values, once per
 *       frame received. That is a first-person cinematic from where the player is standing; the
 *       player is never teleported, and no free-camera Mixin is installed. The frame's
 *       <em>position</em> is not applied either: moving the camera body without a Mixin would
 *       mean moving the player, which is exactly the teleport the v1 decision rules out. The
 *       position does travel, because a sound has to be placed at the camera the frame describes.</li>
 *   <li><b>Titles:</b> {@link CutsceneFrame#titles()} goes to vanilla's title/subtitle API — its
 *       first line as the title, the remaining lines joined as the subtitle. The frame carries
 *       text lines without their authoring style ({@code Track.Title.style} stops at the engine
 *       seam), so line order is the only mapping available; a frame with no titles clears
 *       whatever the previous frame showed, which is how a title ends without a second message.</li>
 *   <li><b>Sounds:</b> each id in {@link CutsceneFrame#sounds()} is resolved against the native
 *       sound registry and played as a positioned client sound at the frame's camera position. An
 *       id with no registered {@link SoundEvent} plays nothing and is reported once
 *       (see {@link #playSound}) — the honest outcome, not a substitute.</li>
 *   <li><b>Actors:</b> ignored. {@link CutsceneFrame#actors()} names engine clips, and the cell
 *       has no engine entity model to perform them yet; the engine's entity types register an
 *       empty renderer, so there is nothing an actor shot could be applied to. Documented gap.</li>
 *   <li><b>FOV:</b> carried on the wire, not applied; 1.21.1 has no per-frame FOV override that a
 *       cell can reach without a Mixin (the value lives in {@code GameRenderer}'s own
 *       computation). Reported once per client, never silently dropped.</li>
 * </ul>
 *
 * <p><b>Ending.</b> The transport sends an end marker when the cutscene stops playing
 * ({@code NeoForgeCutsceneTransport}), and that marker returns the client to normal: the camera
 * is put back to the yaw/pitch it had when the cutscene started, and the title is cleared. A
 * staleness fallback ({@link #STALE_TICKS}) covers a transport that dies without ever sending the
 * marker, so a stuck title cannot outlive a dead server by more than the fallback.
 *
 * <p><b>Where the frames are applied.</b> The payload handler only decodes and parks the frame;
 * application happens on the client tick, because writing the player's rotation or the HUD from
 * the network thread would be a race with the tick that reads them. Arming is split by the
 * loader: the receiver is the clientbound payload handler NeoForge registers from this cell's
 * net package, and {@link #install()} registers the tick that applies what arrived.
 */
public final class VineCutsceneClient {

    private static final Logger LOG = LoggerFactory.getLogger(VineCutsceneClient.class);

    /**
     * Client ticks without a frame before a cutscene counts as over. The server sends one frame
     * per tick while one plays, so two seconds of silence is not a hiccup — it is an ending that
     * arrived without its marker (a stopped or unreachable server). The marker is the contract
     * path; this is the safety net that keeps a title from outliving the server that sent it.
     */
    private static final int STALE_TICKS = 40;

    /**
     * Title timings. The stay is long on purpose: the server re-sends the same line every tick
     * while a title is up, and a re-set with a longer stay than the fade-in means the text is
     * already opaque by the time anyone looks at it (a per-tick restart of a short fade would
     * leave it permanently half-faded).
     */
    private static final int TITLE_FADE_IN_TICKS = 5;
    private static final int TITLE_STAY_TICKS = 200;
    private static final int TITLE_FADE_OUT_TICKS = 10;

    /** One install per process — the tick handler must not be registered twice. */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();

    /** The newest frame from the transport, waiting for the next client tick to be applied. */
    private static final AtomicReference<Incoming> PENDING = new AtomicReference<>();

    // Below: main-thread state only. Written from the client tick, never from the net thread.
    private static VineId active;
    private static int ticksWithoutFrame;
    private static boolean cameraHeld;
    private static float cameraReturnYaw;
    private static float cameraReturnPitch;
    private static List<String> shownTitles = List.of();
    private static final Set<VineId> reportedUnresolvedSounds = new HashSet<>();
    private static boolean fovReported;

    /** A frame as it arrived, with the name of the player the server addressed it to. */
    private record Incoming(CutsceneFramePayload.Decoded frame, String viewerName) {
    }

    private VineCutsceneClient() {
    }

    /**
     * Arms the client half, once, from the client entrypoint: the tick that applies what the
     * frame receiver parked. The receiver itself is the clientbound payload handler this cell's
     * transport registers with NeoForge ({@code NeoForgeCutsceneTransport.bindNative}), which is
     * the loader difference from Fabric's client-registered global receiver.
     */
    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick(Minecraft.getInstance()));
        LOG.info("[VINE] cutscenes: client frame receiver armed (camera yaw/pitch, titles, sounds)");
    }

    /** Decodes a frame the transport delivered and parks it for the next tick to apply. */
    public static void receive(byte[] data, String viewerName) {
        CutsceneFramePayload.Decoded frame;
        try {
            frame = CutsceneFramePayload.decode(data);
        } catch (RuntimeException e) {
            // A corrupt frame is a protocol error, not a crash: the client survives with the
            // previous tick's presentation and says what it refused.
            LOG.error("[VINE] cutscenes: refused a malformed frame ({} byte(s)): {}", data.length, e.toString());
            return;
        }
        PENDING.set(new Incoming(frame, viewerName));
    }

    /** One client tick: apply the frame that arrived, or decide a silent cutscene is over. */
    private static void tick(Minecraft client) {
        Incoming incoming = PENDING.getAndSet(null);
        if (incoming != null) {
            ticksWithoutFrame = 0;
            if (incoming.frame().ended()) {
                end(client, "the server sent the end marker");
            } else {
                apply(client, incoming);
            }
            return;
        }
        if (active != null && ++ticksWithoutFrame >= STALE_TICKS) {
            end(client, "no frame for " + ticksWithoutFrame + " client ticks");
        }
    }

    /** Applies one frame: camera, titles, sounds — in that order, for this tick. */
    private static void apply(Minecraft client, Incoming incoming) {
        LocalPlayer player = client.player;
        if (player == null) {
            // No world to be a camera in (a frame that crossed the disconnect): nothing to apply.
            return;
        }
        CutsceneFramePayload.Decoded frame = incoming.frame();
        if (!frame.cutscene().equals(active)) {
            if (active != null) {
                // One cutscene at a time: a different id means the previous one is over here.
                end(client, "replaced by " + frame.cutscene());
            }
            active = frame.cutscene();
            cameraReturnYaw = player.getYRot();
            cameraReturnPitch = player.getXRot();
        }
        player.setYRot(frame.yawDegrees());
        player.setXRot(frame.pitchDegrees());
        cameraHeld = true;

        if (!frame.titles().equals(shownTitles)) {
            shownTitles = frame.titles();
            if (shownTitles.isEmpty()) {
                client.gui.clear();
            } else {
                client.gui.setTitle(Component.literal(shownTitles.get(0)));
                client.gui.setSubtitle(shownTitles.size() > 1
                    ? Component.literal(String.join("\n", shownTitles.subList(1, shownTitles.size())))
                    : Component.empty());
                client.gui.setTimes(TITLE_FADE_IN_TICKS, TITLE_STAY_TICKS, TITLE_FADE_OUT_TICKS);
            }
        }
        for (VineId sound : frame.sounds()) {
            playSound(client, frame, sound);
        }
        if (!fovReported) {
            fovReported = true;
            LOG.info("[VINE] cutscene frame: fov {} is carried but not applied — 1.21.1 computes FOV inside"
                + " GameRenderer with no per-frame hook, and sub-23 v1 adds no Mixin (documented skip)",
                frame.fov());
        }
        LOG.info("[VINE] cutscene frame applied: id={} tick={} yaw={} pitch={} titles={} sounds={} (viewer {})",
            frame.cutscene(), frame.tick(), frame.yawDegrees(), frame.pitchDegrees(), frame.titles().size(),
            frame.sounds().size(), incoming.viewerName());
    }

    /**
     * Plays one sound of the frame as a positioned client sound at the frame's camera position.
     * An id that resolves to no native {@link SoundEvent} plays nothing and is reported once per
     * id: a sound the cell cannot play is a gap to name, never to fill with a different sound.
     */
    private static void playSound(Minecraft client, CutsceneFramePayload.Decoded frame, VineId sound) {
        ResourceLocation identifier = ResourceLocation.tryParse(sound.toString());
        SoundEvent event = identifier == null ? null : BuiltInRegistries.SOUND_EVENT.getOptional(identifier).orElse(null);
        if (event == null) {
            if (reportedUnresolvedSounds.add(sound)) {
                LOG.info("[VINE] cutscene sound {}: no sound event is registered under that id — nothing played"
                    + " (the authored sound is not registered on this cell)", sound);
            }
            return;
        }
        if (client.level == null) {
            // A frame that outlived its level: no place to put a sound.
            return;
        }
        Vec3 camera = frame.cameraPosition();
        client.level.playLocalSound(camera.x(), camera.y(), camera.z(), event, SoundSource.VOICE, 1.0F, 1.0F, false);
        LOG.info("[VINE] cutscene sound played: {} at camera {}", sound, camera.asString());
    }

    /**
     * Returns the client to normal: the camera goes back to where it was pointing when the
     * cutscene started, and the title is cleared. Called for an end marker, for a replacement,
     * and for the staleness fallback — each of them is the same "this cutscene is over here".
     */
    private static void end(Minecraft client, String reason) {
        if (active == null) {
            return;
        }
        VineId finished = active;
        active = null;
        ticksWithoutFrame = 0;
        if (cameraHeld && client.player != null) {
            client.player.setYRot(cameraReturnYaw);
            client.player.setXRot(cameraReturnPitch);
        }
        cameraHeld = false;
        client.gui.clear();
        shownTitles = List.of();
        LOG.info("[VINE] cutscene {} ended on this client — {} (camera returned to yaw {} pitch {}, title cleared)",
            finished, reason, cameraReturnYaw, cameraReturnPitch);
    }
}
