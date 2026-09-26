package dev.vineengine.vine.internal.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.data.VoxelSchema;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.internal.animation.PoseEvaluator;
import dev.vineengine.vine.internal.spi.PartHost;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.Vec3;

/**
 * The engine's multipart runtime (sub-08 Stage D). One part of an entity is a collider
 * riding an animation bone (geometry from the sub-09 evaluator) plus a piece of stored
 * state (wound, broken, flinch) living in the actor's own tree.
 *
 * <p><b>Why the split.</b> Geometry is a pure function of (asset, clip, time, actor
 * transform) and is recomputed, never stored — so a cell cannot desync from what the
 * animation actually shows. State is data with a save path, so a half-broken tail is
 * still half-broken after a reload. Neither half needs a packet per tick: cells carry
 * the colliders with the bodies the game already syncs, and the engine's state changes
 * only on a hit.
 *
 * <p><b>Determinism.</b> The clip clock is a tick counter advanced once per hosted tick,
 * not a wall clock, so the same hits at the same ticks produce the same pose and the
 * same state on every cell — which is what the golden fixtures check.
 */
public final class PartRuntime {

    /** The schema part state lives under: {@code parts.<name>.{wound,broken,flinch,broken_at}}. */
    public static final VineId SCHEMA = VineId.parse("vine:parts");

    /** Ticks per second the pose clock runs at — the evaluator's own constant, reused. */
    private static final double TICKS_PER_SECOND = PoseEvaluator.TICKS_PER_SECOND;

    private static final Map<VineEntityRef, PartHost> HOSTS = new ConcurrentHashMap<>();
    private static final Map<VineEntityRef, Live> LIVE = new ConcurrentHashMap<>();
    private static boolean schemaRegistered;

    private PartRuntime() {
    }

    /** Registers the part schema; must run before the schema registry freezes. */
    public static synchronized void ensureSchema() {
        if (schemaRegistered) {
            return;
        }
        VineData.registerSchema(new VoxelSchema(SCHEMA, 1, Codec.unit(null)), List.of());
        schemaRegistered = true;
    }

    /** One actor's live hosting: what is playing, and how far into it we are. */
    private static final class Live {

        final AnimationAsset asset;
        String clip;
        long ticks;
        boolean flinchPending;
        String flinchedPart;

        Live(AnimationAsset asset, String clip) {
            this.asset = asset;
            this.clip = clip;
        }
    }

    /**
     * Binds (or refreshes) an actor's host and advances its pose clock by one tick.
     * Called by the cell each server tick; an actor with no host is simply not hosted.
     */
    public static void tick(VineEntityRef ref, PartHost host) {
        Objects.requireNonNull(ref, "ref");
        Objects.requireNonNull(host, "host");
        HOSTS.put(ref, host);
        Live live = LIVE.get(ref);
        if (live != null) {
            live.ticks++;
            // A hit that landed earlier this tick goes out before the tick ends
            // (sub-08 Stage D's "within one tick"): the flush is per actor, so a
            // standing beast costs one set lookup and nothing else.
            PartSync.flush(ref, live.ticks);
        }
    }

    /** Starts hosting: the actor's parts are live, driven by {@code clip} of {@code asset}. */
    public static void attach(VineEntityRef ref, AnimationAsset asset, String clip) {
        requireHosted(ref);
        if (!asset.clips().containsKey(clip)) {
            throw new IllegalArgumentException("asset '" + asset.name() + "' has no clip '" + clip + "' (clips: "
                + asset.clips().keySet() + ")");
        }
        if (descriptor(ref).parts().isEmpty()) {
            throw new IllegalStateException("entity descriptor " + ref.entityId() + " declares no parts — hosting"
                + " parts on it would be a caller bug (clip '" + clip + "')");
        }
        LIVE.put(ref, new Live(asset, clip));
    }

    /** Stops hosting. Stored wound state stays where it is — the actor's tree keeps it. */
    public static void detach(VineEntityRef ref) {
        LIVE.remove(ref);
        HOSTS.remove(ref);
        PartSync.forget(ref);
    }

    public static boolean isHosted(VineEntityRef ref) {
        return LIVE.containsKey(ref);
    }

    /** Switches the clip and restarts its clock at {@code tick}. */
    public static void play(VineEntityRef ref, String clip, long tick) {
        Live live = live(ref);
        if (!live.asset.clips().containsKey(clip)) {
            throw new IllegalArgumentException("asset '" + live.asset.name() + "' has no clip '" + clip
                + "' (clips: " + live.asset.clips().keySet() + ")");
        }
        live.clip = clip;
        live.ticks = Math.max(0L, tick);
    }

    public static List<PartState> parts(VineEntityRef ref) {
        VoxelData state = state(ref);
        List<PartState> out = new ArrayList<>();
        for (PartDescriptor part : descriptor(ref).parts()) {
            out.add(read(state, part.name()));
        }
        return List.copyOf(out);
    }

    public static Optional<PartState> part(VineEntityRef ref, String name) {
        if (partOf(ref, name) == null) {
            return Optional.empty();
        }
        return Optional.of(read(state(ref), name));
    }

    public static Optional<OrientedBox> box(VineEntityRef ref, String name, Vec3 position, float yawDegrees) {
        PartDescriptor part = partOf(ref, name);
        if (part == null) {
            return Optional.empty();
        }
        SkeletonPose pose = pose(ref).orElseThrow(() -> new IllegalStateException(
            "actor " + ref + " has no pose — parts are hosted only while a clip is playing"));
        return Optional.of(PoseEvaluator.partBox(pose, part, position, yawDegrees));
    }

    public static Optional<SkeletonPose> pose(VineEntityRef ref) {
        Live live = LIVE.get(ref);
        if (live == null) {
            return Optional.empty();
        }
        AnimationAsset.Clip clip = live.asset.clips().get(live.clip);
        double seconds = live.ticks / TICKS_PER_SECOND;
        if (clip.loop() && clip.lengthSeconds() > 0.0D) {
            seconds = seconds % clip.lengthSeconds();
        } else {
            seconds = Math.min(seconds, clip.lengthSeconds());
        }
        return Optional.of(PoseEvaluator.pose(live.asset, live.clip, seconds));
    }

    /** Where the host says the actor is — used when the caller does not supply a transform. */
    public static Optional<OrientedBox> boxAtHost(VineEntityRef ref, String name) {
        PartHost host = HOSTS.get(ref);
        if (host == null) {
            return Optional.empty();
        }
        return box(ref, name, host.position(), host.yawDegrees());
    }

    /**
     * Applies one hit to one part through the *hit zone*: the multiplier comes from the
     * descriptor and the broken state, which is the path a raw hit takes (an example, a
     * scenario, a foreign damage source the engine only observes).
     */
    public static Optional<VineParts.PartHit> applyHit(VineEntityRef ref, String name, double amount,
            VineId damageType) {
        PartDescriptor part = partOf(ref, name);
        if (part == null) {
            return Optional.empty();
        }
        double multiplier = PartState.multiplierOf(part, damageType, read(state(ref), name).broken());
        return record(ref, part, amount, amount * multiplier);
    }

    /**
     * Applies an already-resolved amount to one part — the path the combat pipeline's
     * APPLY uses, where SWEEP chose the part and RESOLVE (and the modifiers after it)
     * already multiplied the number. Applying the hit zone a second time here would make
     * every weapon hit its own multiplier twice, which is the kind of bug a worked
     * example catches and nothing else does.
     */
    public static Optional<VineParts.PartHit> applyResolved(VineEntityRef ref, String name, double amount) {
        PartDescriptor part = partOf(ref, name);
        if (part == null) {
            return Optional.empty();
        }
        return record(ref, part, amount, amount);
    }

    /** The shared bookkeeping: wound, break, flinch, storage, and the dirty flag. */
    private static Optional<VineParts.PartHit> record(VineEntityRef ref, PartDescriptor part, double rawAmount,
            double applied) {
        String name = part.name();
        Live live = live(ref);
        VoxelData state = state(ref);
        PartState before = read(state, name);
        boolean broke = !before.broken() && part.breakThreshold() > 0.0F
            && before.wound() + applied >= part.breakThreshold();
        double sinceFlinch = state.getDouble(path(name, "since_flinch")) + applied;
        boolean flinched = part.flinchThreshold() > 0.0F && sinceFlinch >= part.flinchThreshold();
        if (flinched) {
            live.flinchPending = true;
            live.flinchedPart = name;
        }
        // Damage is quantized at the storage boundary, exactly as the wire quantizes it:
        // a float multiplier (0.8F is 0.800000011920929) would otherwise leave the server
        // holding 32.00000047683716 while every client reconstructs 32.0, and the two would
        // disagree about whether a break threshold had been crossed. Hundredths is far
        // finer than any damage number a game prints.
        // The amount that *lands* is the quantized one: a float multiplier (1.3F is
        // 1.300000011920929) would otherwise report 51.99999809265137 while the stored wound
        // says 52.0, and a consumer comparing the two would think the engine lost a hit.
        double landed = quantized(applied);
        PartState after = before.woundedBy(landed).broken(before.broken() || broke);
        write(state, after, flinched ? live.ticks : 0L, flinched ? 0.0D : sinceFlinch, broke);
        PartSync.markDirty(ref);
        return Optional.of(new VineParts.PartHit(name, rawAmount, landed, broke, flinched, after));
    }

    /** A damage amount rounded to the wire's fixed point: the value everyone shares. */
    private static double quantized(double amount) {
        return PartSync.quantize(amount);
    }

    /** Takes the pending flinch, if any: a flinch interrupts exactly once. */
    public static boolean consumeFlinch(VineEntityRef ref) {
        Live live = LIVE.get(ref);
        if (live == null || !live.flinchPending) {
            return false;
        }
        live.flinchPending = false;
        live.flinchedPart = null;
        return true;
    }

    /** How many actors are hosted — a probe for tests and the dashboard. */
    public static int hostedCount() {
        return LIVE.size();
    }

    // ------------------------------------------------------------------ internals

    private static Live live(VineEntityRef ref) {
        Live live = LIVE.get(ref);
        if (live == null) {
            throw new IllegalStateException("actor " + ref + " has no parts hosted — call VineParts.attach first"
                + " (and the cell must be ticking the actor)");
        }
        return live;
    }

    private static void requireHosted(VineEntityRef ref) {
        if (!HOSTS.containsKey(ref)) {
            throw new IllegalStateException("actor " + ref + " is not hosted by the running cell: parts need the"
                + " actor's body, and only a cell knows where it stands");
        }
    }

    private static EntityDescriptor descriptor(VineEntityRef ref) {
        return VineRegistries.<EntityDescriptor>get(VineContent.ENTITY_TYPE, ref.entityId())
            .map(holder -> holder.value())
            .orElseThrow(() -> new IllegalStateException("entity descriptor " + ref.entityId()
                + " is not registered — an actor of unregistered content cannot have parts"));
    }

    /** The descriptor's part with {@code name}, or {@code null} when the actor has none. */
    private static PartDescriptor partOf(VineEntityRef ref, String name) {
        for (PartDescriptor part : descriptor(ref).parts()) {
            if (part.name().equals(name)) {
                return part;
            }
        }
        return null;
    }

    private static VoxelData state(VineEntityRef ref) {
        PartHost host = HOSTS.get(ref);
        if (host == null) {
            throw new IllegalStateException("actor " + ref + " is not hosted by the running cell");
        }
        return host.state();
    }

    private static String path(String partName, String field) {
        return "parts." + partName + "." + field;
    }

    private static PartState read(VoxelData state, String name) {
        return new PartState(name,
            state.getDouble(path(name, "wound")),
            state.contains(path(name, "broken")) && state.getByte(path(name, "broken")) != 0,
            state.contains(path(name, "flinch_tick")) ? state.getLong(path(name, "flinch_tick")) : Long.MIN_VALUE);
    }

    private static void write(VoxelData state, PartState part, long flinchTick, double sinceFlinch, boolean broke) {
        state.put(path(part.name(), "wound"), part.wound());
        state.put(path(part.name(), "broken"), (byte) (part.broken() ? 1 : 0));
        if (flinchTick > 0L) {
            state.put(path(part.name(), "flinch_tick"), flinchTick);
        }
        state.put(path(part.name(), "since_flinch"), sinceFlinch);
        if (broke) {
            state.put(path(part.name(), "broken_at"), part.wound());
        }
    }
}
