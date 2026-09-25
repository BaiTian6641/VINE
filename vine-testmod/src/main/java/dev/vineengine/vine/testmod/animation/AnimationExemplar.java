package dev.vineengine.vine.testmod.animation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.animation.Quaternion;
import dev.vineengine.vine.animation.SkeletonPose;
import dev.vineengine.vine.animation.TimingWindows;
import dev.vineengine.vine.animation.VineAnimations;
import dev.vineengine.vine.entity.PartDescriptor;
import dev.vineengine.vine.world.Vec3;

/**
 * The sub-09 exemplar on a live cell: the same asset the headless fixture harness
 * evaluates, evaluated here through the engine facade and reported as digests.
 *
 * <p>Why both: the harness (a plain JVM, no driver, no Minecraft) proves the evaluator
 * is headless and deterministic; this exemplar proves the *cell's* engine exposes the
 * same answer, so a consumer's combat code cannot depend on something only one of the
 * two can see. The scenario asserts the digests, so a divergence between the harness and
 * a cell — or between the two cells — is a failure rather than a discovery.
 *
 * <p>The asset is read from the classpath exactly as a consumer's own asset would be; no
 * path is hard-coded into the engine, and nothing here touches a client class.
 */
public final class AnimationExemplar {

    /** The fixture asset shipped as a consumer resource. */
    private static final String ASSET_RESOURCE = "assets/vine_test/vine/animation/wyvern_stub.json";

    /** The clips the fixture declares. */
    private static final String IDLE = "animation.vine_test.wyvern_stub.idle";

    private static final String STRIKE = "animation.vine_test.wyvern_stub.strike";

    /** The instant inside the strike's active window that gets digested. */
    private static final double SAMPLE_SECONDS = 0.45D;

    /** The part whose world box is digested: the head, offset from its bone's pivot. */
    private static final PartDescriptor HEAD = new PartDescriptor("head", "head",
        Vec3.of(1.4D, 1.4D, 1.6D), Vec3.of(0.0D, 0.6D, 0.0D), java.util.Map.of(), 0.0F, 0.0F);

    private AnimationExemplar() {
    }

    /** Evaluates the fixture and prints the digests the scenario pins. */
    public static void probe() throws IOException {
        String json;
        try (InputStream in = AnimationExemplar.class.getClassLoader().getResourceAsStream(ASSET_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("the testmod resource " + ASSET_RESOURCE + " is missing from the"
                    + " cell's classpath — the exemplar cannot run");
            }
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        AnimationAsset asset = VineAnimations.parse(json);
        TimingWindows strike = VineAnimations.windows(asset, STRIKE);
        TimingWindows idle = VineAnimations.windows(asset, IDLE);
        SkeletonPose pose = VineAnimations.pose(asset, STRIKE, SAMPLE_SECONDS);

        System.out.println("tck: anim asset=" + asset.name() + " bones=" + asset.bones().size() + " clips="
            + asset.clips().size());
        System.out.println("tck: anim windows strike=" + windows(strike) + " idle=" + windows(idle));
        System.out.println("tck: anim poseDigest=" + poseDigest(pose));
        System.out.println("tck: anim partBoxDigest=" + partBoxDigest(pose));
    }

    /** A clip's windows as one stable string. */
    private static String windows(TimingWindows windows) {
        return "startup=" + windows.startupTicks() + " active=" + windows.activeTicks() + " recovery="
            + windows.recoveryTicks() + " cancel=" + windows.cancelTicks() + " total=" + windows.totalTicks();
    }

    /** Every bone's model-space transform, in sorted order, hashed — the pose fingerprint. */
    private static String poseDigest(SkeletonPose pose) {
        StringBuilder text = new StringBuilder();
        for (String bone : pose.boneNames()) {
            SkeletonPose.BonePose bonePose = pose.bone(bone);
            text.append(bone).append('|')
                .append(bonePose.translation().asString()).append('|')
                .append(quaternion(bonePose.rotation())).append('|')
                .append(bonePose.scale().asString()).append('\n');
        }
        return sha256(text.toString());
    }

    /** The head part's world box at the sampled instant, hashed — the geometry fingerprint. */
    private static String partBoxDigest(SkeletonPose pose) {
        var box = VineAnimations.partBox(pose, HEAD, Vec3.of(0.0D, 64.0D, 0.0D), 30.0F);
        StringBuilder text = new StringBuilder();
        text.append("center|").append(box.center().asString()).append('\n')
            .append("half|").append(box.halfExtents().asString()).append('\n')
            .append("rotation|").append(quaternion(box.rotation())).append('\n');
        for (Vec3 corner : box.corners()) {
            text.append("corner|").append(corner.asString()).append('\n');
        }
        return sha256(text.toString());
    }

    private static String quaternion(Quaternion q) {
        return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", q.x(), q.y(), q.z(), q.w());
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must exist on every supported JVM", e);
        }
    }
}
