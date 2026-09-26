package dev.vineengine.vine.testmod.parts;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.animation.VineAnimations;
import dev.vineengine.vine.content.VineContent;
import dev.vineengine.vine.entity.EntityDescriptor;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.entity.VineEntities;
import dev.vineengine.vine.entity.VineEntityRef;
import dev.vineengine.vine.entity.VineParts;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.registry.VineRegistries;
import dev.vineengine.vine.world.Vec3;
import dev.vineengine.vine.world.VineWorlds;

/**
 * The sub-08 Stage D exemplar: a spawned beast whose parts are driven by the engine's
 * own pose evaluator, hit by damage type, and followed through wound, flinch and break —
 * on a live cell, through public API only.
 *
 * <p>This is also the ease-of-use yardstick for multipart creatures: the whole
 * consumer-side story is four engine calls (attach, applyHit, part, box) plus reading
 * the hit's outcome, while everything a cell has to do about parts happens underneath
 * them.
 */
public final class PartsExemplar {

    /** The authoring asset the testmod ships. */
    private static final String ASSET_RESOURCE = "assets/vine_test/vine/animation/wyvern_stub.json";

    /** The looping clip the parts ride here. */
    private static final String IDLE_CLIP = "animation.vine_test.wyvern_stub.idle";

    /** Damage type, spelled the way a consumer spells it. */
    private static final VineId PLAYER_ATTACK = VineId.parse("minecraft:player_attack");

    /** The actor this exemplar last spawned, so the scenario can ask about it a tick later. */
    private static VineEntityRef last;
    /** The pose reading of the previous {@link #track()} call, so movement is asserted as a delta. */
    private static double lastPoseSeconds = Double.NaN;
    private static Vec3 lastStart;
    private static OrientedBox lastBox;

    private PartsExemplar() {
    }

    /**
     * Spawns one beast at {@code start}. Part hosting starts a tick later, in
     * {@link #hit()}: a cell binds an actor's host when it ticks that actor's body, and a
     * body spawned mid-tick has not been ticked yet — so the scenario spawns, advances,
     * then hits, which is also how a real consumer would do it.
     *
     * @return {@code false} when the cell refused the spawn (reported, never guessed)
     */
    public static boolean spawn(Vec3 start) {
        var spawned = VineEntities.spawn(VineId.of("vine_test", "testbeast"), VineWorlds.overworld(), start);
        if (spawned.isEmpty()) {
            System.out.println("tck: parts spawn refused at=" + start.asString());
            return false;
        }
        last = spawned.get();
        lastStart = start;
        System.out.println("tck: parts spawned ref=" + last + " at=" + start.asString());
        return true;
    }

    /**
     * Hosts the beast's declared parts over the idle clip, runs the fixture's hit script
     * through the engine, and prints one line per fact the scenario asserts.
     */
    public static void hit() {
        VineEntityRef ref = last;
        if (ref == null) {
            System.out.println("tck: parts hit none");
            return;
        }
        AnimationAsset asset = asset();
        VineParts.attach(ref, asset, IDLE_CLIP);
        System.out.println("tck: parts hosted=" + VineParts.isHosted(ref)
            + " parts=" + names(VineParts.parts(ref)));

        // Head, twice: the first lands for the declared player_attack multiplier
        // (40 x 1.25 = 50), the second crosses the part's cumulative flinch threshold (60).
        report("head-hit-1", VineParts.applyHit(ref, "head", 40.0D, PLAYER_ATTACK));
        report("head-hit-2", VineParts.applyHit(ref, "head", 40.0D, PLAYER_ATTACK));
        System.out.println("tck: parts flinch consumed=" + VineParts.consumeFlinch(ref)
            + " again=" + VineParts.consumeFlinch(ref));

        // Tail, three times: 150 against a 120 break threshold breaks it, and a broken
        // part is softer — the next hit lands for the broken factor.
        report("tail-hit-1", VineParts.applyHit(ref, "tail", 50.0D, PLAYER_ATTACK));
        report("tail-hit-2", VineParts.applyHit(ref, "tail", 50.0D, PLAYER_ATTACK));
        report("tail-hit-3", VineParts.applyHit(ref, "tail", 50.0D, PLAYER_ATTACK));
        report("tail-hit-4", VineParts.applyHit(ref, "tail", 10.0D, PLAYER_ATTACK));

        // Geometry follows the animation rather than a stored copy: the box at the current
        // clip time is the evaluator's own answer for the actor's own transform.
        Optional<SkeletonPose> pose = VineParts.pose(ref);
        Optional<OrientedBox> box = VineParts.box(ref, "head", lastStart, 0.0F);
        System.out.println("tck: parts pose=" + pose.map(p -> Double.toString(p.seconds())).orElse("none")
            + " box=" + box.map(b -> b.center().asString()).orElse("none")
            + " matchesEvaluator=" + matchesEvaluator(pose, box, ref, lastStart));
        lastBox = box.orElse(null);
    }

    /**
     * Reports where the exemplar's beast is a few ticks later: the pose clock's own
     * reading, whether the box has moved since the previous report, and whether it is
     * still the evaluator's own answer for the current pose. The scenario calls this
     * before and after an {@code AdvanceTicks}, which is how "geometry tracks the pose"
     * becomes an assertion instead of a claim.
     */
    public static void track() {
        if (last == null) {
            System.out.println("tck: parts track none");
            return;
        }
        Optional<SkeletonPose> pose = VineParts.pose(last);
        Optional<OrientedBox> box = VineParts.box(last, "head", lastStart, 0.0F);
        boolean moved = box.isPresent() && lastBox != null && !box.get().center().equals(lastBox.center());
        // The absolute pose depends on when the host was first ticked, which depends on how
        // many ticks the runner spent issuing commands. What the engine guarantees is that the
        // clock advances with the ticks, so the scenario asserts the delta.
        double seconds = pose.map(SkeletonPose::seconds).orElse(Double.NaN);
        String delta = Double.isNaN(lastPoseSeconds) || Double.isNaN(seconds) ? "none"
            : Double.toString(seconds - lastPoseSeconds);
        lastPoseSeconds = seconds;
        System.out.println("tck: parts track pose=" + Double.toString(seconds)
            + " poseDelta=" + delta
            + " boxMoved=" + moved
            + " matchesEvaluator=" + matchesEvaluator(pose, box, last, lastStart));
        lastBox = box.orElse(null);
    }

    /**
     * Prints the exemplar beast's part state — what a scripted client's swing actually did,
     * read from the engine rather than inferred from the swing that caused it.
     */
    public static void status() {
        if (last == null) {
            System.out.println("tck: parts status none");
            return;
        }
        StringBuilder out = new StringBuilder("tck: parts status");
        for (PartState part : VineParts.parts(last)) {
            out.append(' ').append(part.name()).append('=').append(part.wound())
                .append(part.broken() ? "(broken)" : "");
        }
        out.append(" flinched=").append(VineParts.consumeFlinch(last));
        System.out.println(out);
    }

    /** Prints one hit's outcome — the engine's numbers, never a re-computation. */
    private static void report(String label, Optional<VineParts.PartHit> hit) {
        if (hit.isEmpty()) {
            System.out.println("tck: parts " + label + " missing-part");
            return;
        }
        VineParts.PartHit result = hit.get();
        System.out.println("tck: parts " + label
            + " raw=" + result.rawAmount()
            + " applied=" + result.appliedAmount()
            + " wound=" + result.state().wound()
            + " broken=" + result.broke()
            + " flinched=" + result.flinched());
    }

    /**
     * Whether the engine's box is the evaluator's own answer for the current pose — the
     * "geometry tracks the pose" claim, checked rather than restated.
     */
    private static boolean matchesEvaluator(Optional<SkeletonPose> pose, Optional<OrientedBox> box,
            VineEntityRef ref, Vec3 position) {
        if (pose.isEmpty() || box.isEmpty()) {
            return false;
        }
        return VineAnimations.partBox(pose.get(), headOf(ref), position, 0.0F).equals(box.get());
    }

    /** The declared {@code head} part of the spawned descriptor. */
    private static PartDescriptor headOf(VineEntityRef ref) {
        return VineRegistries.<EntityDescriptor>get(VineContent.ENTITY_TYPE, ref.entityId())
            .flatMap(holder -> holder.value().parts().stream().filter(p -> p.name().equals("head")).findFirst())
            .orElseThrow(() -> new IllegalStateException("the testbeast descriptor declares no 'head' part"));
    }

    private static String names(List<PartState> parts) {
        StringBuilder out = new StringBuilder("[");
        for (PartState part : parts) {
            out.append(out.length() == 1 ? "" : ",").append(part.name());
        }
        return out.append(']').toString();
    }

    /**
     * The testmod's authoring asset, parsed through the engine — shared with the combat
     * exemplar so both scenarios host parts over the same bones the asset actually has.
     */
    public static AnimationAsset asset() {
        try (InputStream in = PartsExemplar.class.getClassLoader().getResourceAsStream(ASSET_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the testmod resource " + ASSET_RESOURCE + " is missing from the"
                    + " testmod jar");
            }
            return VineAnimations.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + ASSET_RESOURCE, e);
        }
    }
}
