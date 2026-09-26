package dev.vineengine.vine.internal.tck;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.OrientedBox;
import dev.vineengine.vine.animation.Quaternion;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.animation.TimingWindows;
import dev.vineengine.vine.animation.VineAnimations;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.entity.PartState;
import dev.vineengine.vine.internal.entity.PartDelta;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.world.Vec3;

/**
 * The sub-09 golden-fixture harness: renders the evaluator's canonical output for the
 * testmod's authoring asset and byte-compares it with the committed goldens under
 * {@code vine-tck/fixtures/animation/}.
 *
 * <p>What it proves, and why the classpath matters more than the code:
 *
 * <ul>
 *   <li><b>Determinism.</b> The rendered text is byte-compared, not tolerance-compared, and
 *       the numbers are printed with {@link Double#toString} — so a single bit of drift in
 *       the evaluator's arithmetic is a failing fixture, and running the task twice is
 *       byte-identical. The comparison covers every bone's model-space transform at several
 *       sample times per clip, a bone-attached locator, both clips' windows tick by tick,
 *       and the world-space boxes of parts on rotated bones.</li>
 *   <li><b>Headlessness.</b> The Gradle task runs this class with a classpath of
 *       {@code vine-api} + {@code vine-core} + their libraries and nothing else — no driver,
 *       no testmod, no Minecraft, no LWJGL. The classpath is printed as evidence, and client
 *       classes are probed for by name: an evaluator that reached for one would fail here with
 *       a linkage error rather than pass by convention.</li>
 * </ul>
 *
 * <p>Exit codes: {@code 0} every fixture matches, {@code 1} a fixture drifted or could not be
 * read, {@code 2} bad arguments, {@code 3} the classpath is not the headless one.
 */
public final class EvaluatorFixtureRunner {

    /** The authored fixture asset, read from the repo path (sub-09 §5). */
    private static final String ASSET_RELATIVE =
        "vine-testmod/src/main/resources/assets/vine_test/vine/animation/wyvern_stub.json";

    /** The looping clip of the fixture asset. */
    private static final String IDLE = "animation.vine_test.wyvern_stub.idle";
    /** The one-shot clip whose markers declare a hit window. */
    private static final String STRIKE = "animation.vine_test.wyvern_stub.strike";

    /** Sample times per clip; 2.4 wraps into the looping clip, so the wrap rule is pinned too. */
    private static final Map<String, List<Double>> SAMPLES = Map.of(
        IDLE, List.of(0.0D, 0.25D, 0.4D, 1.0D, 1.6D, 2.0D, 2.4D),
        STRIKE, List.of(0.0D, 0.15D, 0.3D, 0.45D, 0.55D, 0.7D, 0.85D, 1.0D));

    /** The fixed bone-local offset every pose's locator is taken at. */
    private static final Vec3 PROBE_OFFSET = Vec3.of(0.5D, -0.25D, 0.75D);

    /** Client-only classes the evaluator must not be able to reach: present means not headless. */
    private static final List<String> CLIENT_CLASSES = List.of(
        "net.minecraft.client.Minecraft",
        "software.bernie.geckolib.GeckoLib",
        "com.geckolib.GeckoLib");

    /** Classpath entries that would mean the harness is not the headless one it claims to be. */
    private static final Pattern FOREIGN_ENTRY = Pattern.compile("(?i)(minecraft|fabric|neoforge|lwjgl|forge)");

    private EvaluatorFixtureRunner() {
    }

    public static void main(String[] args) throws IOException {
        Path rootDir = null;
        Path fixturesDir = null;
        Path asset = null;
        boolean write = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root-dir" -> rootDir = Path.of(args[++i]);
                case "--fixtures-dir" -> fixturesDir = Path.of(args[++i]);
                case "--asset" -> asset = Path.of(args[++i]);
                case "--write" -> write = true;
                default -> {
                    System.err.println("[TCK] evaluatorFixtures: unknown argument: " + args[i]);
                    usage();
                }
            }
        }
        if (rootDir == null || fixturesDir == null) {
            usage();
        }
        if (asset == null) {
            asset = rootDir.resolve(ASSET_RELATIVE);
        }
        if (!printClasspath()) {
            System.err.println("[TCK] evaluatorFixtures FAIL — the classpath is not the headless one"
                + " (see the entry above); the task must run against vine-api + vine-core only");
            System.exit(3);
        }
        ROOT_DIR = rootDir;
        if (!Files.isRegularFile(asset)) {
            System.err.println("[TCK] evaluatorFixtures FAIL — no fixture asset at " + asset);
            System.exit(1);
        }
        Map<String, String> rendered;
        try {
            rendered = render(VineAnimations.parse(Files.readString(asset, StandardCharsets.UTF_8)), rootDir);
        } catch (RuntimeException e) {
            System.err.println("[TCK] evaluatorFixtures FAIL — the evaluator rejected fixture asset "
                + asset + ": " + e);
            // A harness that swallows the stack is a harness nobody can debug: print it.
            e.printStackTrace();
            System.exit(1);
            return;
        }
        System.out.println("[TCK] evaluatorFixtures: asset " + asset);
        System.exit(write ? write(fixturesDir, rendered) : compare(fixturesDir, rendered));
    }

    private static void usage() {
        System.err.println("usage: EvaluatorFixtureRunner --root-dir <dir> --fixtures-dir <dir>"
            + " [--asset <file>] [--write]");
        System.err.println("  compare mode is the default; --write (re)creates the goldens");
        System.exit(2);
    }

    // ------------------------------------------------------------------
    // the headless classpath, printed and checked
    // ------------------------------------------------------------------

    /**
     * Prints every classpath entry and probes for client-only classes. Returns {@code false}
     * when something on the classpath is not part of the headless set — a misconfiguration is
     * loud, never a silent pass.
     */
    private static boolean printClasspath() {
        String raw = System.getProperty("java.class.path", "");
        String[] entries = raw.isEmpty() ? new String[0] : raw.split(Pattern.quote(File.pathSeparator));
        System.out.println("[TCK] evaluatorFixtures classpath: " + entries.length + " entr"
            + (entries.length == 1 ? "y" : "ies") + " — vine-api + vine-core + their libraries"
            + " (no driver, testmod, Minecraft or LWJGL jar)");
        boolean clean = true;
        for (String entry : entries) {
            System.out.println("[TCK]   " + entry);
            if (entry.isBlank()) {
                continue;
            }
            Path path = Path.of(entry).getFileName();
            String name = path == null ? entry : path.toString();
            if (FOREIGN_ENTRY.matcher(name).find()) {
                System.out.println("[TCK]   ^ FOREIGN — this entry is not part of the headless evaluator"
                    + " classpath");
                clean = false;
            }
        }
        for (String clientClass : CLIENT_CLASSES) {
            try {
                Class.forName(clientClass);
                System.out.println("[TCK] headless probe: " + clientClass + " -> PRESENT on the classpath");
                clean = false;
            } catch (ClassNotFoundException e) {
                System.out.println("[TCK] headless probe: " + clientClass
                    + " -> absent (ClassNotFoundException), as the headless evaluator requires");
            }
        }
        return clean;
    }

    // ------------------------------------------------------------------
    // rendering the canonical output
    // ------------------------------------------------------------------

    /** The canonical text of both golden files, keyed by file name. */
    private static Map<String, String> render(AnimationAsset asset, Path rootDir) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("wyvern_stub.poses.txt", renderPoses(asset));
        files.put("wyvern_stub.windows.txt", renderWindows(asset));
        files.put("parts.delta.txt", renderParts());
        files.put("combat.txt", renderCombat());
        files.put("cutscene.frames.txt", renderCutscene(ROOT_DIR));
        files.put("ui.layout.txt", renderUi(ROOT_DIR));
        return files;
    }

    /**
     * The pose table: every bone's model-space translation, rotation, scale and a
     * bone-attached locator, at each sample time of each clip, followed by the world boxes of
     * parts riding rotated bones.
     */
    private static String renderPoses(AnimationAsset asset) {
        StringBuilder out = new StringBuilder();
        out.append("# VINE sub-09 evaluator golden — canonical poses\n");
        out.append("# Unit: blocks and degrees at the asset boundary, model space here; the pose is\n");
        out.append("# the server-tick canonical one (sub-09 §2). Numbers are Double.toString, so a\n");
        out.append("# single bit of evaluator drift fails this file.\n");
        out.append("asset ").append(asset.name()).append('\n');
        out.append("bones ").append(String.join(",", sorted(asset.bones().keySet()))).append('\n');
        out.append("clips ").append(String.join(",", sorted(asset.clips().keySet()))).append('\n');
        out.append("locator-probe-offset ").append(vector(PROBE_OFFSET)).append('\n');
        for (String clip : sorted(asset.clips().keySet())) {
            for (double requested : SAMPLES.get(clip)) {
                SkeletonPose pose = VineAnimations.pose(asset, clip, requested);
                out.append("sample clip=").append(clip)
                    .append(" requested=").append(Double.toString(requested))
                    .append(" effective=").append(Double.toString(pose.seconds())).append('\n');
                for (String bone : pose.boneNames()) {
                    SkeletonPose.BonePose bonePose = pose.bone(bone);
                    out.append("  bone ").append(bone)
                        .append(" translation=").append(vector(bonePose.translation()))
                        .append(" rotation=").append(quaternion(bonePose.rotation()))
                        .append(" scale=").append(vector(bonePose.scale()))
                        .append(" locator=").append(vector(pose.locator(bone, PROBE_OFFSET)))
                        .append('\n');
                }
            }
        }
        out.append("part-boxes\n");
        for (PartProbe probe : PART_PROBES) {
            SkeletonPose pose = VineAnimations.pose(asset, probe.clip(), probe.time());
            OrientedBox box = VineAnimations.partBox(pose, probe.part(), probe.actor(), probe.yawDegrees());
            out.append("  part ").append(probe.part().name())
                .append(" bone=").append(probe.part().parentBone())
                .append(" size=").append(vector(probe.part().size()))
                .append(" offset=").append(vector(probe.part().offset())).append('\n');
            out.append("    pose clip=").append(probe.clip())
                .append(" requested=").append(Double.toString(probe.time()))
                .append(" effective=").append(Double.toString(pose.seconds()))
                .append(" actor=").append(vector(probe.actor()))
                .append(" yaw=").append(Double.toString(probe.yawDegrees())).append('\n');
            out.append("    center=").append(vector(box.center()))
                .append(" halfExtents=").append(vector(box.halfExtents()))
                .append(" rotation=").append(quaternion(box.rotation())).append('\n');
            Vec3[] corners = box.corners();
            for (int i = 0; i < corners.length; i++) {
                out.append("    corner").append(i).append('=').append(vector(corners[i])).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * The cutscene golden (sub-23 Stage A): the authored cutscene parsed through its own
     * codec, then evaluated tick by tick. It is the same claim the evaluator goldens make —
     * a frame is a pure function of the descriptor and the tick — and it also proves the
     * JSON authoring path, because the descriptor here comes from the fixture file rather
     * than from a constructor.
     */
    private static String renderCutscene(Path rootDir) {
        StringBuilder out = new StringBuilder();
        out.append("# VINE sub-23 Stage A golden — cutscene frames\n");
        out.append("# Parsed from the authored JSON through CutsceneDescriptor.CODEC, so a change\n");
        out.append("# in the authoring shape or in the evaluation fails here.\n");
        Path fixture = rootDir.resolve(CUTSCENE_RELATIVE);
        dev.vineengine.vine.cutscene.CutsceneDescriptor descriptor;
        try {
            com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString(
                Files.readString(fixture, StandardCharsets.UTF_8));
            var parsed = dev.vineengine.vine.cutscene.CutsceneDescriptor.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, json);
            descriptor = parsed.result().orElseThrow(() -> new IllegalStateException(
                "the authored cutscene JSON was rejected by its own codec: "
                    + parsed.error().map(com.mojang.serialization.DataResult.Error::message).orElse("unknown")));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the cutscene fixture at " + fixture, e);
        }
        out.append("cutscene ").append(descriptor.id())
            .append(" length=").append(descriptor.lengthTicks())
            .append(" tracks=").append(descriptor.tracks().size())
            .append(" skippable=").append(descriptor.skippable()).append('\n');
        for (dev.vineengine.vine.cutscene.Track track : descriptor.tracks()) {
            out.append("  track ").append(track.typeName())
                .append(" start=").append(track.startTick())
                .append(" end=").append(track.endTick()).append('\n');
        }
        for (long tick : new long[] {0L, 1L, 10L, 19L, 20L, 21L, 30L, 32L, 39L, 40L, 41L, 50L, 59L}) {
            dev.vineengine.vine.cutscene.CutsceneFrame frame =
                dev.vineengine.vine.internal.cutscene.CutsceneRuntime.evaluate(descriptor, tick);
            out.append("frame tick=").append(frame.tick())
                .append(" camera=").append(vector(frame.camera().position()))
                .append(" yaw=").append(Float.toString(frame.camera().yawDegrees()))
                .append(" pitch=").append(Float.toString(frame.camera().pitchDegrees()))
                .append(" fov=").append(Float.toString(frame.camera().fov()))
                .append(" actors=").append(frame.actors().size())
                .append(" sounds=").append(frame.sounds().size())
                .append(" titles=").append(frame.titles().size())
                .append('\n');
            for (dev.vineengine.vine.cutscene.CutsceneFrame.ActorShot actor : frame.actors()) {
                out.append("    actor ").append(actor.actor())
                    .append(" clip=").append(actor.clip())
                    .append(" seconds=").append(Double.toString(actor.seconds())).append('\n');
            }
            for (VineId sound : frame.sounds()) {
                out.append("    sound ").append(sound).append('\n');
            }
            for (String title : frame.titles()) {
                out.append("    title ").append(title).append('\n');
            }
        }
        return out.toString();
    }

    /** The repository root, set once from {@code --root-dir} before rendering. */
    private static Path ROOT_DIR;

    /**
     * The UI layout golden (sub-16 Stage A): the authored screen parsed through its own codec
     * and resolved by the engine's solver, for the fixture frame and for a second scale. If a
     * cell ever disagreed about where a button is, this file is what would notice.
     */
    private static String renderUi(Path rootDir) {
        StringBuilder out = new StringBuilder();
        out.append("# VINE sub-16 Stage A golden — screen layout resolution\n");
        out.append("# Logical pixels in, absolute pixels out: the arithmetic every cell shares.\n");
        Path fixture = rootDir.resolve(SCREEN_RELATIVE);
        dev.vineengine.vine.ui.ScreenDescriptor screen;
        try {
            com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString(
                Files.readString(fixture, StandardCharsets.UTF_8));
            var parsed = dev.vineengine.vine.ui.ScreenDescriptor.CODEC
                .parse(com.mojang.serialization.JsonOps.INSTANCE, json);
            screen = parsed.result().orElseThrow(() -> new IllegalStateException(
                "the authored screen JSON was rejected by its own codec: "
                    + parsed.error().map(com.mojang.serialization.DataResult.Error::message).orElse("unknown")));
        } catch (IOException e) {
            throw new IllegalStateException("could not read the screen fixture at " + fixture, e);
        }
        out.append("screen ").append(screen.id())
            .append(" pausesGame=").append(screen.pausesGame())
            .append(" widgets=").append(dev.vineengine.vine.internal.ui.UiLayout.widgetIds(screen)).append('\n');
        int[][] frames = {{320, 240}, {427, 240}};
        for (int[] frame : frames) {
            out.append("frame ").append(frame[0]).append('x').append(frame[1]).append(" logical\n");
            for (var entry : dev.vineengine.vine.internal.ui.UiLayout
                    .resolve(screen, frame[0], frame[1]).entrySet()) {
                out.append("  widget ").append(entry.getKey())
                    .append(" x=").append(entry.getValue().x())
                    .append(" y=").append(entry.getValue().y())
                    .append(" w=").append(entry.getValue().width())
                    .append(" h=").append(entry.getValue().height())
                    .append(" hitAtCentre=").append(entry.getValue().contains(
                        entry.getValue().x() + entry.getValue().width() / 2,
                        entry.getValue().y() + entry.getValue().height() / 2))
                    .append('\n');
            }
        }
        return out.toString();
    }

    /** Where the authored screen fixture lives, relative to the repo root. */
    private static final String SCREEN_RELATIVE =
        "vine-testmod/src/main/resources/data/vine_test/vine/screen/hunt_board.json";

    /** Where the authored cutscene fixture lives, relative to the repo root. */
    private static final String CUTSCENE_RELATIVE =
        "vine-testmod/src/main/resources/data/vine_test/vine/cutscene/wyvern_strike.json";

    /**
     * The combat golden (sub-10 Stage C): the plan's own worked example, the damage
     * arithmetic every cell shares, and the partner preset the engine cooks for a
     * partner-owned weapon. No driver, no registry, no Minecraft — a pure function of the
     * descriptors below, which is what makes it a golden rather than a smoke test.
     */
    private static String renderCombat() {
        StringBuilder out = new StringBuilder();
        out.append("# VINE sub-10 Stage C golden — worked example and partner cooking\n");
        out.append("# The numbers are the plan's own (docs/subsystem notes, §5): the chain must end\n");
        out.append("# at 110 damage, rounded half-up exactly once, at APPLY.\n");
        double base = 40.0D;
        double motion = 1.6D;
        double hitzone = 1.3D;
        double affinity = 1.1D;
        double element = 1.2D;
        double afterMotion = base * motion;
        double afterHitzone = afterMotion * hitzone;
        double afterAffinity = afterHitzone * affinity;
        double afterElement = afterAffinity * element;
        long applied = Math.round(afterElement);
        out.append("worked-example base=").append(Double.toString(base))
            .append(" motion=").append(Double.toString(motion)).append(" afterMotion=")
            .append(Double.toString(afterMotion)).append('\n');
        out.append("worked-example hitzone=").append(Double.toString(hitzone)).append(" afterHitzone=")
            .append(Double.toString(afterHitzone)).append('\n');
        out.append("worked-example affinity=").append(Double.toString(affinity)).append(" afterAffinity=")
            .append(Double.toString(afterAffinity)).append('\n');
        out.append("worked-example element=").append(Double.toString(element)).append(" afterElement=")
            .append(Double.toString(afterElement)).append('\n');
        out.append("worked-example applied=").append(applied)
            .append(" halfUp=true knockback=(2.5,0.4,0.0) hitstop=3\n");

        VineId item = VineId.of("vine_test", "banana_blade");
        VineId preset = VineId.of("bettercombat", "claymore");
        dev.vineengine.vine.combat.AttackDescriptor attack = new dev.vineengine.vine.combat.AttackDescriptor(
            VineId.of("vine_test", "wide_slash"), 1.6D, VineId.parse("minecraft:player_attack"),
            VineId.of("vine_test", "ember"),
            dev.vineengine.vine.combat.SweepShape.Box.of(2.8D, 1.2D, 1.2D),
            Vec3.of(2.0D, 0.0D, 0.0D), 3);
        dev.vineengine.vine.combat.CombatProfile profile = dev.vineengine.vine.combat.CombatProfile.partner(
            VineId.of("vine_test", "wide_slash"), dev.vineengine.vine.combat.CombatOwnership.BETTER_COMBAT, 40.0D,
            preset);
        var cooked = dev.vineengine.vine.internal.compat.PartnerPresetCooking.cook(item, profile, attack)
            .orElseThrow(() -> new IllegalStateException("a partner-owned weapon must cook a preset file"));
        out.append("cooked-path ").append(cooked.path()).append('\n');
        out.append("cooked-bytes ").append(cooked.content().length()).append('\n');
        for (String line : cooked.content().split("\n", -1)) {
            out.append("  |").append(line).append('\n');
        }
        // Two cooks of the same descriptor must be the same bytes: the property the
        // cross-cell byte-identity claim rests on.
        String again = dev.vineengine.vine.internal.compat.PartnerPresetCooking.cook(item, profile, attack)
            .orElseThrow().content();
        out.append("cooked-repeatable ").append(again.equals(cooked.content())).append('\n');
        return out.toString();
    }

    /**
     * The parts golden (sub-08 Stage D): a fixed hit script against the fixture's own
     * declared parts, the state it produces, the delta bytes it would put on the wire,
     * the receiver's round trip of those bytes, and the budget arithmetic the stage
     * promises. All of it is pure — {@code PartState} and {@code PartDelta} take values
     * and return values — which is why it belongs in this headless harness rather than
     * in a live cell test: a layout change or a multiplier change fails here, on a
     * classpath with no Minecraft on it at all.
     */
    private static String renderParts() {
        StringBuilder out = new StringBuilder();
        out.append("# VINE sub-08 Stage D golden — parts, hits and deltas\n");
        out.append("# Mirrors the parts declared in data/vine_test/vine/entity/testbeast.json; the\n");
        out.append("# live scenario covers that descriptor on a real cell, this file covers the\n");
        out.append("# arithmetic every cell shares. Damage type ids use the dagger convention below.\n");
        List<PartDescriptor> parts = List.of(
            new PartDescriptor("head", "head", Vec3.of(1.4D, 1.4D, 1.6D), Vec3.of(0.0D, 2.4D, -0.9D),
                Map.of(VineId.of("minecraft", "player_attack"), 1.3F, VineId.of("minecraft", "arrow"), 0.8F),
                60.0F, 0.0F),
            new PartDescriptor("tail", "tail", Vec3.of(1.0D, 1.0D, 2.6D), Vec3.of(0.0D, 1.6D, 2.2D),
                Map.of(VineId.of("minecraft", "player_attack"), 1.0F), 0.0F, 120.0F));
        for (PartDescriptor part : parts) {
            out.append("part ").append(part.name())
                .append(" bone=").append(part.parentBone())
                .append(" size=").append(vector(part.size()))
                .append(" offset=").append(vector(part.offset()))
                .append(" flinch=").append(Float.toString(part.flinchThreshold()))
                .append(" break=").append(Float.toString(part.breakThreshold()))
                .append(" multipliers=");
            List<String> declared = new ArrayList<>();
            for (Map.Entry<VineId, Float> entry : new java.util.TreeMap<>(part.damageMultipliers()).entrySet()) {
                declared.add(entry.getKey() + "=" + entry.getValue());
            }
            out.append(String.join(",", declared)).append('\n');
        }
        out.append("broken-multiplier-factor ").append(Double.toString(PartState.BROKEN_FACTOR)).append('\n');
        out.append("quantization-divisor 100 (fixed point, hundredths of a damage point)\n");

        record Hit(String part, double amount, String type) {
        }
        List<Hit> script = List.of(
            new Hit("head", 40.0D, "minecraft:player_attack"),
            new Hit("head", 40.0D, "minecraft:player_attack"),
            new Hit("head", 40.0D, "minecraft:arrow"),
            new Hit("tail", 50.0D, "minecraft:player_attack"),
            new Hit("tail", 50.0D, "minecraft:player_attack"),
            new Hit("tail", 50.0D, "minecraft:player_attack"),
            new Hit("tail", 10.0D, "minecraft:player_attack"));

        List<PartState> state = new ArrayList<>();
        for (PartDescriptor part : parts) {
            state.add(PartState.fresh(part.name()));
        }
        double[] sinceFlinch = new double[parts.size()];
        PartDelta.Sent sent = new PartDelta.Sent(parts.size());
        long tick = 100L;
        boolean flinchPending = false;
        for (int step = 0; step < script.size(); step++) {
            Hit hit = script.get(step);
            PartDescriptor descriptor = parts.stream().filter(p -> p.name().equals(hit.part())).findFirst()
                .orElseThrow();
            int index = parts.indexOf(descriptor);
            PartState before = state.get(index);
            double applied = hit.amount() * PartState.multiplierOf(descriptor, VineId.parse(hit.type()),
                before.broken());
            // The runtime's rule, restated here so the golden would catch a divergence:
            // a part flinches when the damage since its last flinch crosses the threshold,
            // and the accumulator resets at that moment.
            sinceFlinch[index] += applied;
            boolean flinched = descriptor.flinchThreshold() > 0.0F
                && sinceFlinch[index] >= descriptor.flinchThreshold();
            if (flinched) {
                sinceFlinch[index] = 0.0D;
            }
            boolean broke = !before.broken() && descriptor.breakThreshold() > 0.0F
                && before.wound() + applied >= descriptor.breakThreshold();
            // The runtime quantizes at the storage boundary (the wire's own fixed point),
            // so the golden must too, or it would be pinning a value no cell stores.
            PartState after = before.woundedBy(PartDelta.quantize(applied) / 100.0D)
                .broken(before.broken() || broke);
            if (flinched) {
                after = after.flinchedAt(tick);
                flinchPending = true;
            }
            state.set(index, after);
            byte[] payload = PartDelta.encode(state, sent, tick);
            List<PartState> roundTrip = payload == null ? List.copyOf(state) : PartDelta.apply(state, payload);
            boolean identical = roundTrip.equals(state);
            out.append("hit ").append(step + 1)
                .append(" part=").append(hit.part())
                .append(" amount=").append(Double.toString(hit.amount()))
                .append(" type=").append(hit.type())
                .append(" applied=").append(Double.toString(applied))
                .append(" wound=").append(Double.toString(after.wound()))
                .append(" broken=").append(after.broken())
                .append(" flinched=").append(flinched)
                .append(" flinchPending=").append(flinchPending)
                .append(" deltaBytes=").append(payload == null ? "none" : Integer.toString(payload.length))
                .append(" deltaHex=").append(payload == null ? "none" : hex(payload))
                .append(" roundTrip=").append(identical ? "identical" : "MISMATCH")
                .append('\n');
            if (flinchPending) {
                // consumeFlinch(): the latch is taken once, exactly like the runtime's.
                flinchPending = false;
            }
            tick += 3L;
        }
        int worstCase = PartDelta.worstCaseBytes(12);
        out.append("worst-case parts=12 bytes=").append(worstCase)
            .append(" perSecond=").append(worstCase * 20)
            .append(" at=20tps limit=2560 ok=").append(worstCase * 20 <= 2560).append('\n');
        return out.toString();
    }

    /** Hex, lower case, no separators — a byte layout that cannot hide. */
    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
        }
        return out.toString();
    }

    /** The clip timing table: markers, the derived windows, and every tick's phase. */
    private static String renderWindows(AnimationAsset asset) {
        StringBuilder out = new StringBuilder();
        out.append("# VINE sub-09 evaluator golden — clip windows at 20 TPS\n");
        out.append("# Markers come from the asset's own effect maps under VINE's namespace; a clip with\n");
        out.append("# none is recovery for its whole length (sub-09 §2, TimingWindows).\n");
        out.append("asset ").append(asset.name()).append('\n');
        for (String clipName : sorted(asset.clips().keySet())) {
            AnimationAsset.Clip clip = asset.clips().get(clipName);
            TimingWindows windows = VineAnimations.windows(asset, clipName);
            out.append("clip ").append(clipName)
                .append(" loop=").append(clip.loop())
                .append(" length=").append(Double.toString(clip.lengthSeconds())).append('\n');
            for (AnimationAsset.Marker marker : clip.markers()) {
                out.append("  marker ").append(Double.toString(marker.timeSeconds()))
                    .append(' ').append(marker.name()).append('\n');
            }
            out.append("  window startup=").append(windows.startupTicks())
                .append(" active=").append(windows.activeTicks())
                .append(" recovery=").append(windows.recoveryTicks())
                .append(" cancel=").append(windows.cancelTicks())
                .append(" total=").append(windows.totalTicks()).append('\n');
            for (TimingWindows.Window span : windows.asWindows()) {
                out.append("  span ").append(span.phase())
                    .append(" [").append(span.fromTick()).append(',').append(span.toTick()).append(")\n");
            }
            for (int tick = 0; tick < windows.totalTicks(); tick++) {
                String phase = windows.isActive(tick) ? "active"
                    : tick < windows.startupTicks() ? "startup" : "recovery";
                out.append("  tick ").append(tick).append(' ').append(phase)
                    .append(windows.cancellable(tick) ? " cancellable" : "").append('\n');
            }
            out.append("  tick ").append(windows.totalTicks()).append(" end\n");
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // writing and comparing
    // ------------------------------------------------------------------

    private static int write(Path fixturesDir, Map<String, String> rendered) throws IOException {
        Files.createDirectories(fixturesDir);
        for (Map.Entry<String, String> fixture : rendered.entrySet()) {
            byte[] bytes = fixture.getValue().getBytes(StandardCharsets.UTF_8);
            Path target = fixturesDir.resolve(fixture.getKey());
            Files.write(target, bytes);
            System.out.println("[TCK] evaluatorFixtures: wrote " + target + " (" + bytes.length
                + " bytes, sha256 " + sha256(bytes) + ")");
        }
        System.out.println("[TCK] evaluatorFixtures: " + rendered.size() + " golden(s) written"
            + " — run without --write to compare");
        return 0;
    }

    private static int compare(Path fixturesDir, Map<String, String> rendered) throws IOException {
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, String> fixture : rendered.entrySet()) {
            byte[] current = fixture.getValue().getBytes(StandardCharsets.UTF_8);
            Path golden = fixturesDir.resolve(fixture.getKey());
            if (!Files.isRegularFile(golden)) {
                failures.add(fixture.getKey() + ": no golden at " + golden + " — run with --write to"
                    + " create it");
                continue;
            }
            byte[] committed = Files.readAllBytes(golden);
            if (Arrays.equals(committed, current)) {
                System.out.println("[TCK] evaluatorFixtures: " + fixture.getKey() + " OK (" + current.length
                    + " bytes, sha256 " + sha256(current) + ")");
                continue;
            }
            failures.add(fixture.getKey() + ": bytes differ from " + golden);
            System.out.println("[TCK] evaluatorFixtures: DRIFT in " + fixture.getKey());
            System.out.println("[TCK]   golden  " + committed.length + " bytes, sha256 "
                + sha256(committed));
            System.out.println("[TCK]   current " + current.length + " bytes, sha256 " + sha256(current));
            List<String> goldenLines = lines(committed);
            List<String> currentLines = lines(current);
            int reported = 0;
            int differing = 0;
            int count = Math.max(goldenLines.size(), currentLines.size());
            for (int i = 0; i < count; i++) {
                String left = i < goldenLines.size() ? goldenLines.get(i) : "<missing>";
                String right = i < currentLines.size() ? currentLines.get(i) : "<missing>";
                if (left.equals(right)) {
                    continue;
                }
                differing++;
                if (reported < 3) {
                    reported++;
                    System.out.println("[TCK]   line " + (i + 1) + " golden  |" + left + "|");
                    System.out.println("[TCK]   line " + (i + 1) + " current |" + right + "|");
                }
            }
            System.out.println("[TCK]   " + differing + " differing line(s)");
        }
        if (!failures.isEmpty()) {
            failures.forEach(failure -> System.out.println("[TCK] evaluatorFixtures FAIL — " + failure));
            return 1;
        }
        System.out.println("[TCK] evaluatorFixtures: " + rendered.size()
            + " golden(s) byte-identical to the committed fixtures");
        return 0;
    }

    private static List<String> lines(byte[] bytes) {
        return List.of(new String(bytes, StandardCharsets.UTF_8).split("\n", -1));
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(Character.forDigit((value >> 4) & 0xF, 16)).append(Character.forDigit(value & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every JVM", e);
        }
    }

    private static List<String> sorted(Iterable<String> values) {
        List<String> sorted = new ArrayList<>();
        values.forEach(sorted::add);
        sorted.sort(null);
        return sorted;
    }

    private static String vector(Vec3 value) {
        return "(" + Double.toString(value.x()) + "," + Double.toString(value.y()) + ","
            + Double.toString(value.z()) + ")";
    }

    private static String quaternion(Quaternion value) {
        return "(" + Double.toString(value.x()) + "," + Double.toString(value.y()) + ","
            + Double.toString(value.z()) + "," + Double.toString(value.w()) + ")";
    }

    // ------------------------------------------------------------------
    // the parts the geometry goldens cover
    // ------------------------------------------------------------------

    /** One part box to render: which part, on which pose, for which actor. */
    private record PartProbe(PartDescriptor part, String clip, double time, Vec3 actor, float yawDegrees) {
    }

    /**
     * Parts chosen so the boxes are meaningful: {@code head} carries a non-identity rest
     * rotation plus an animated one, and {@code wing_left} is mid-swing with a non-unit scale
     * channel on the strike clip — the case an axis-aligned box would get wrong.
     */
    private static final List<PartProbe> PART_PROBES = List.of(
        new PartProbe(new PartDescriptor("head_strike", "head", Vec3.of(0.6D, 0.6D, 0.8D),
            Vec3.of(0.0D, 0.0D, -0.4D), Map.of(), 0.0F, 0.0F), STRIKE, 0.3D,
            Vec3.of(12.5D, 70.0D, -3.25D), 0.0F),
        new PartProbe(new PartDescriptor("head_strike", "head", Vec3.of(0.6D, 0.6D, 0.8D),
            Vec3.of(0.0D, 0.0D, -0.4D), Map.of(), 0.0F, 0.0F), STRIKE, 0.45D,
            Vec3.of(12.5D, 70.0D, -3.25D), 30.0F),
        new PartProbe(new PartDescriptor("wing_tip", "wing_left", Vec3.of(0.8D, 0.3D, 1.2D),
            Vec3.of(1.2D, 0.0D, 0.0D), Map.of(), 0.0F, 0.0F), IDLE, 0.4D,
            Vec3.of(12.5D, 70.0D, -3.25D), 135.5F));
}
