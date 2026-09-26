package dev.vineengine.vine.internal.cutscene;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.cutscene.CameraKey;
import dev.vineengine.vine.cutscene.CutsceneDescriptor;
import dev.vineengine.vine.cutscene.CutsceneFrame;
import dev.vineengine.vine.cutscene.Track;
import dev.vineengine.vine.internal.CutsceneBackend;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.Vec3;

/**
 * The engine's cutscene runtime (sub-23 Stages A+B): evaluates a frame for a tick, and plays
 * one cutscene at a time for a chosen set of viewers.
 *
 * <p><b>Evaluation is pure.</b> A frame is a function of (descriptor, tick) and nothing else
 * — no world reads, no wall clock — which is why the golden fixture can pin every sample
 * tick on a plain JVM, and why two viewers are guaranteed the same moment rather than merely
 * likely to be near it.
 *
 * <p><b>Viewers are explicit.</b> A cutscene does not capture the world: the server names who
 * is watching, and everyone else keeps playing. That is also what makes a late joiner cheap —
 * they are handed the frame for the current tick, not a private timeline from zero.
 *
 * <p><b>One at a time (v1).</b> Starting a cutscene replaces whatever was playing, and the
 * old one ends for its viewers. Two concurrent cinematics is a story problem before it is a
 * technical one.
 */
public final class CutsceneRuntime implements CutsceneBackend {

    /** Ticks per second the runtime advances at, shared with the rest of the engine. */
    private static final double TICKS_PER_SECOND = 20.0D;

    /** Delivers a frame to one viewer; installed by a cell's transport, discarding by default. */
    @FunctionalInterface
    public interface Sender {

        void send(UUID viewer, VineId cutscene, CutsceneFrame frame);
    }

    private static volatile Sender sender = (viewer, cutscene, frame) -> {
        // No transport bound (headless boot): frames are still produced and counted, so a
        // scenario can check exactly what a client would have received.
    };

    /** Plays one cutscene: which one, for whom, and how far in. */
    private static final class Playing {

        final CutsceneDescriptor descriptor;
        final Set<UUID> viewers;
        long tick;
        boolean ended;

        Playing(CutsceneDescriptor descriptor, Set<UUID> viewers) {
            this.descriptor = descriptor;
            this.viewers = viewers;
        }
    }

    private final AtomicReference<Playing> playing = new AtomicReference<>();
    private final List<java.util.function.BiConsumer<UUID, CutsceneFrame>> frameListeners =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private long framesProduced;

    /** Installs the frame transport; {@code null} restores the discarding default. */
    public static void sender(Sender installed) {
        sender = installed == null ? (viewer, cutscene, frame) -> {
        } : installed;
    }

    @Override
    public void play(VineId cutscene, Set<UUID> viewers) {
        CutsceneDescriptor descriptor = VineRegistries.<CutsceneDescriptor>get(VineContent.CUTSCENE_TYPE, cutscene)
            .map(holder -> holder.value())
            .orElseThrow(() -> new IllegalArgumentException("no cutscene is registered under " + cutscene));
        Playing next = new Playing(descriptor, new LinkedHashSet<>(viewers));
        Playing previous = playing.getAndSet(next);
        if (previous != null && previous.viewers.equals(next.viewers)) {
            // One cutscene at a time: the old one ends for the same people.
            previous.ended = true;
        }
    }

    @Override
    public void stop() {
        Playing current = playing.getAndSet(null);
        if (current != null) {
            current.ended = true;
        }
    }

    @Override
    public boolean playing() {
        Playing current = playing.get();
        return current != null && !current.ended;
    }

    @Override
    public Optional<VineId> current() {
        Playing current = playing.get();
        return current == null || current.ended ? Optional.empty() : Optional.of(current.descriptor.id());
    }

    @Override
    public Set<UUID> viewers() {
        Playing current = playing.get();
        return current == null || current.ended ? Set.of() : Set.copyOf(current.viewers);
    }

    @Override
    public void tickCutscenes() {
        Playing current = playing.get();
        if (current == null || current.ended) {
            return;
        }
        if (current.tick >= current.descriptor.lengthTicks()) {
            current.ended = true;
            playing.compareAndSet(current, null);
            return;
        }
        CutsceneFrame frame = evaluate(current.descriptor, current.tick);
        for (UUID viewer : current.viewers) {
            sender.send(viewer, current.descriptor.id(), frame);
            for (java.util.function.BiConsumer<UUID, CutsceneFrame> listener : frameListeners) {
                listener.accept(viewer, frame);
            }
        }
        framesProduced++;
        current.tick++;
    }

    @Override
    public Optional<CutsceneFrame> frame() {
        Playing current = playing.get();
        return current == null || current.ended ? Optional.empty()
            : Optional.of(evaluate(current.descriptor, current.tick));
    }

    @Override
    public CutsceneFrame evaluate(VineId cutscene, long tick) {
        CutsceneDescriptor descriptor = VineRegistries.<CutsceneDescriptor>get(VineContent.CUTSCENE_TYPE, cutscene)
            .map(holder -> holder.value())
            .orElseThrow(() -> new IllegalArgumentException("no cutscene is registered under " + cutscene));
        return evaluate(descriptor, tick);
    }

    @Override
    public void addFrameListener(java.util.function.BiConsumer<UUID, CutsceneFrame> listener) {
        frameListeners.add(listener);
    }

    /** How many frames have been produced — the number a scenario asserts against. */
    public long framesProduced() {
        return framesProduced;
    }

    /** The pure evaluation, for the facade and the runtime alike. */
    public static CutsceneFrame evaluate(CutsceneDescriptor descriptor, long tick) {
        List<CutsceneFrame.ActorShot> actors = new ArrayList<>();
        List<VineId> sounds = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        CutsceneFrame.CameraShot camera = null;
        for (Track track : descriptor.tracks()) {
            if (track instanceof Track.Camera cameraTrack) {
                camera = cameraAt(cameraTrack, tick);
            } else if (track instanceof Track.Actor actor && tick >= actor.startTick() && tick <= actor.endTick()) {
                double seconds = (tick - actor.startTick()) / TICKS_PER_SECOND;
                actors.add(new CutsceneFrame.ActorShot(actor.actor(), actor.clip(), seconds));
            } else if (track instanceof Track.Audio audio && audio.tick() == tick) {
                sounds.add(audio.sound());
            } else if (track instanceof Track.Title title && tick >= title.startTick() && tick <= title.endTick()) {
                titles.add(title.text());
            }
        }
        Objects.requireNonNull(camera, "a cutscene descriptor always has a camera track (its own constructor"
            + " refuses one that does not)");
        return new CutsceneFrame(tick, camera, actors, sounds, titles);
    }

    /** The camera's state at {@code tick}: the surrounding keys, eased by the earlier one. */
    static CutsceneFrame.CameraShot cameraAt(Track.Camera track, long tick) {
        List<CameraKey> keys = track.keys();
        CameraKey before = keys.get(0);
        CameraKey after = null;
        for (CameraKey key : keys) {
            if (key.tick() <= tick) {
                before = key;
            } else {
                after = key;
                break;
            }
        }
        if (after == null || before.easing() == CameraKey.Easing.CUT) {
            return shot(before);
        }
        double span = after.tick() - before.tick();
        double progress = span <= 0.0D ? 1.0D : (tick - before.tick()) / span;
        double shaped = switch (before.easing()) {
            case SMOOTH -> progress * progress * (3.0D - 2.0D * progress);
            default -> progress;
        };
        return new CutsceneFrame.CameraShot(
            lerp(before.position(), after.position(), shaped),
            wrapYaw((float) (before.yawDegrees() + (after.yawDegrees() - before.yawDegrees()) * shaped)),
            (float) (before.pitchDegrees() + (after.pitchDegrees() - before.pitchDegrees()) * shaped),
            (float) (before.fov() + (after.fov() - before.fov()) * shaped));
    }

    private static CutsceneFrame.CameraShot shot(CameraKey key) {
        return new CutsceneFrame.CameraShot(key.position(), key.yawDegrees(), key.pitchDegrees(), key.fov());
    }

    private static Vec3 lerp(Vec3 from, Vec3 to, double t) {
        return Vec3.of(from.x() + (to.x() - from.x()) * t, from.y() + (to.y() - from.y()) * t,
            from.z() + (to.z() - from.z()) * t);
    }

    /** Yaw in [−180, 180): the same span a player's own yaw is read in. */
    private static float wrapYaw(float yaw) {
        float wrapped = yaw % 360.0F;
        if (wrapped >= 180.0F) {
            wrapped -= 360.0F;
        }
        if (wrapped < -180.0F) {
            wrapped += 360.0F;
        }
        return wrapped;
    }
}
