package dev.vineengine.vine.testmod.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import dev.vineengine.vine.capability.CapabilityTarget;
import dev.vineengine.vine.capability.VineCapabilities;
import dev.vineengine.vine.data.VineData;
import dev.vineengine.vine.data.VoxelData;
import dev.vineengine.vine.registry.VineId;
import dev.vineengine.vine.session.SessionAction;
import dev.vineengine.vine.session.SessionPhase;
import dev.vineengine.vine.session.SessionScope;
import dev.vineengine.vine.session.VineSession;
import dev.vineengine.vine.session.VineSessions;
import dev.vineengine.vine.testmod.capability.CapabilityExemplar;
import dev.vineengine.vine.testmod.data.VoxelExemplar;
import dev.vineengine.vine.testmod.session.SessionExemplar;

/**
 * Golden-fixture exemplar (sub-21 Stage C, goal criterion 4): writes one
 * fixture file carrying <b>VoxelData + capability + session</b> state through
 * {@code VineData.encode}, and reads it back on the other loader cell —
 * proving data written on one cell reads back identically on the other
 * (digest byte-identity + semantic equality per section).
 *
 * <p><b>Path contract:</b> the fixed relative path resolves against the dev
 * server's working directory ({@code drivers/driver-X/}) to the shared
 * {@code vine-tck/build/fixtures/} directory, visible to both cells.
 *
 * <p>Consumer-pure: engine types + JDK only, identical sources on every cell.
 */
public final class FixtureExemplar {

    private static final Path FIXTURE_DIR = Path.of("..", "..", "vine-tck", "build", "fixtures");
    private static final Path FIXTURE_FILE = FIXTURE_DIR.resolve("roundtrip.bin");
    private static final Path DIGEST_FILE = FIXTURE_DIR.resolve("roundtrip.digest");
    private static final byte[] MAGIC = "VINEFIX1".getBytes(StandardCharsets.US_ASCII);

    private FixtureExemplar() {
    }

    /** Builds the three state trees: portable voxel fields, capability, session. */
    private static VoxelData[] buildSections() {
        // 1. VoxelData exemplar tree: portable mana=100 + native damage=3.
        VoxelData voxel = VoxelExemplar.createTree();

        // 2. Capability state: one attached mana instance mutated to 42, then
        //    serialized through its codec — the capability state contract.
        CapabilityTarget target = new CapabilityTarget.ItemCapabilityTarget("fixture-item");
        CapabilityExemplar.Mana mana = VineCapabilities.find(CapabilityExemplar.MANA_TYPE, target)
            .orElseThrow(() -> new IllegalStateException("mana capability not attached"));
        mana.setAmount(42);
        VoxelData capability = CapabilityExemplar.MANA_TYPE.state().save(mana);

        // 3. Session state: one live hunt with an objective score.
        VineSession session = VineSessions.manager().create(SessionExemplar.HUNT_TYPE,
            new SessionScope.World(VineId.of("vine_test", "arena")),
            VineData.create(VineId.of("vine_test", "hunt_params")));
        VineSessions.manager().transition(session.id(), SessionPhase.ACTIVE);
        session.state().objectives().put("score", 12);
        session.submit(new SessionAction(UUID.nameUUIDFromBytes("fixture".getBytes()),
            VineId.of("vine_test", "advance"), VineData.create(VineId.of("vine_test", "hunt_params"))));
        VoxelData sessionState = session.state().objectives();

        return new VoxelData[] {voxel, capability, sessionState};
    }

    private static final String[] SECTION_NAMES = {"voxel", "capability", "session"};

    /** Command entry: writes the fixture + digest. */
    public static void write() {
        try {
            writeFixture();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("fixture write failed: " + e, e);
        }
    }

    private static void writeFixture() throws IOException {
        VoxelData[] sections = buildSections();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write(MAGIC);
        out.write(sections.length);
        for (int i = 0; i < sections.length; i++) {
            byte[] name = SECTION_NAMES[i].getBytes(StandardCharsets.US_ASCII);
            byte[] blob = VineData.encode(sections[i]);
            out.write(name.length);
            out.write(name);
            out.write((blob.length >>> 24) & 0xFF);
            out.write((blob.length >>> 16) & 0xFF);
            out.write((blob.length >>> 8) & 0xFF);
            out.write(blob.length & 0xFF);
            out.write(blob);
        }
        Files.createDirectories(FIXTURE_DIR);
        byte[] bytes = out.toByteArray();
        Files.write(FIXTURE_FILE, bytes);
        String digest = sha256(bytes);
        Files.writeString(DIGEST_FILE, digest, StandardCharsets.US_ASCII);
        System.out.println("vine-testmod: fixture written sections=voxel,capability,session digest=" + digest);
    }

    /** Command entry: reads + verifies the fixture written on the other cell. */
    public static void read() {
        try {
            readFixture();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("fixture read failed: " + e, e);
        }
    }

    private static void readFixture() throws IOException {
        byte[] bytes = Files.readAllBytes(FIXTURE_FILE);
        String expected = Files.readString(DIGEST_FILE, StandardCharsets.US_ASCII).trim();
        String actual = sha256(bytes);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                "fixture digest mismatch: expected " + expected + " actual " + actual);
        }
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(bytes);
        byte[] magic = in.readNBytes(MAGIC.length);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IllegalStateException("fixture magic mismatch");
        }
        int count = in.read();
        if (count != SECTION_NAMES.length) {
            throw new IllegalStateException("fixture section count mismatch: " + count);
        }
        VoxelData voxel = null;
        VoxelData capability = null;
        VoxelData session;
        int sessionScore = -1;
        int capabilityMana = -1;
        int voxelMana = -1;
        for (int i = 0; i < count; i++) {
            int nameLen = in.read();
            String name = new String(in.readNBytes(nameLen), StandardCharsets.US_ASCII);
            int len = (in.read() << 24) | (in.read() << 16) | (in.read() << 8) | in.read();
            VoxelData tree = VineData.decode(in.readNBytes(len));
            switch (name) {
                case "voxel" -> {
                    voxel = tree;
                    voxelMana = tree.getInt(VoxelExemplar.MANA_PATH);
                }
                case "capability" -> {
                    capability = tree;
                    capabilityMana = tree.getInt("amount");
                }
                case "session" -> sessionScore = tree.getInt("score");
                default -> throw new IllegalStateException("unknown fixture section " + name);
            }
        }
        if (voxelMana != 100 || capabilityMana != 42 || sessionScore != 12) {
            throw new IllegalStateException("fixture semantics mismatch: voxel.mana=" + voxelMana
                + " capability.mana=" + capabilityMana + " session.score=" + sessionScore);
        }
        System.out.println("vine-testmod: fixture verified voxel.mana=100 capability.mana=42 session.score=12");
        System.out.println("vine-testmod: fixture digest match " + actual);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
