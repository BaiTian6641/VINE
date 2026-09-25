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
        if (!Files.isRegularFile(asset)) {
            System.err.println("[TCK] evaluatorFixtures FAIL — no fixture asset at " + asset);
            System.exit(1);
        }
        Map<String, String> rendered;
        try {
            rendered = render(VineAnimations.parse(Files.readString(asset, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            System.err.println("[TCK] evaluatorFixtures FAIL — the evaluator rejected fixture asset "
                + asset + ": " + e);
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
    private static Map<String, String> render(AnimationAsset asset) {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("wyvern_stub.poses.txt", renderPoses(asset));
        files.put("wyvern_stub.windows.txt", renderWindows(asset));
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
