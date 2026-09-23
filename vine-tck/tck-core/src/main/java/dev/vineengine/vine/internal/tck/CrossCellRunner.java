package dev.vineengine.vine.internal.tck;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Golden-fixture cross-cell round-trip (sub-21 Stage C, goal criterion 4):
 * boots the NeoForge cell, runs {@code vine_test tck_fixture_write}, stops it;
 * boots the Fabric cell, runs {@code vine_test tck_fixture_read}, stops it.
 * Exit 0 only when the write produced a fixture and the read verified it —
 * byte-identical digest plus semantic equality per section
 * (VoxelData + capability + session state, see the testmod's FixtureExemplar).
 *
 * <p>Single JVM drives both cells sequentially, so the fixture file handoff
 * between loader families is deterministic regardless of Gradle task ordering.
 * Process control reuses the ScenarioRunner/BootSmoke invariants: child
 * {@code --no-daemon} with its own project cache, pinned jar tasks in the same
 * invocation, stdin-driven console, graceful stop with tree-kill fallback.
 */
public final class CrossCellRunner {

    private static final Duration BOOT_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration ASSERT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration STOP_GRACE = Duration.ofSeconds(90);

    private final Path rootDir;
    private final Path projectCacheDir;
    private final List<String> log = new java.util.ArrayList<>();
    private Process server;
    private BufferedWriter stdin;

    private CrossCellRunner(Path rootDir, Path projectCacheDir) {
        this.rootDir = rootDir;
        this.projectCacheDir = projectCacheDir;
    }

    public static void main(String[] args) throws Exception {
        Path rootDir = null;
        Path projectCacheDir = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--root-dir" -> rootDir = Path.of(args[++i]);
                case "--project-cache-dir" -> projectCacheDir = Path.of(args[++i]);
                default -> {
                    System.err.println("[TCK] unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (rootDir == null || projectCacheDir == null) {
            System.err.println("usage: CrossCellRunner --root-dir <path> --project-cache-dir <path>");
            System.exit(2);
        }
        System.exit(new CrossCellRunner(rootDir, projectCacheDir).run());
    }

    private int run() throws Exception {
        Path fixtureDir = rootDir.resolve("vine-tck/build/fixtures");
        if (Files.exists(fixtureDir)) {
            try (Stream<Path> w = Files.walk(fixtureDir)) {
                w.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // best-effort
                    }
                });
            }
        }

        String writeFailure = drive("1.21.1-neoforge", "vine_test tck_fixture_write",
                "fixture written");
        if (writeFailure != null) {
            System.out.println("[TCK] cross-cell: FAIL on write cell — " + writeFailure);
            return 1;
        }
        String readFailure = drive("1.21.1-fabric", "vine_test tck_fixture_read",
                "fixture verified", "fixture digest match");
        if (readFailure != null) {
            System.out.println("[TCK] cross-cell: FAIL on read cell — " + readFailure);
            return 1;
        }
        System.out.println("[TCK] cross-cell: PASS — fixture written on 1.21.1-neoforge "
            + "verified on 1.21.1-fabric (digest + semantics)");
        return 0;
    }

    /** Boots a cell, sends one command, awaits all expected markers, stops. */
    private String drive(String cell, String command, String... expectedMarkers) throws Exception {
        System.out.println("[TCK] cross-cell: driving " + cell + " -> " + command);
        startServer(cell);
        String bootFailure = awaitBoot();
        if (bootFailure != null) {
            killTree();
            return bootFailure;
        }
        send(command);
        for (String marker : expectedMarkers) {
            String failure = await(0, line -> line.contains(marker), "marker '" + marker + "'");
            if (failure != null) {
                killTree();
                return failure;
            }
        }
        stopServer();
        return null;
    }

    private void startServer(String cell) throws IOException {
        synchronized (log) {
            log.clear();
        }
        List<String> cmd = new java.util.ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("gradlew.bat");
        } else {
            cmd.add("./gradlew");
        }
        cmd.add("--no-daemon");
        cmd.add("--console=plain");
        cmd.add("--project-cache-dir");
        cmd.add(projectCacheDir.toString());
        cmd.add("-Pvine.tck.loopback=true");
        cmd.addAll(List.of(":vine-api:jar", ":vine-core:jar", ":vine-spi:jar", ":vine-testmod:jar"));
        cmd.add(":drivers:driver-" + cell + ":runServer");

        server = new ProcessBuilder(cmd).directory(rootDir.toFile()).redirectErrorStream(true).start();
        stdin = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (log) {
                        log.add(line);
                        log.notifyAll();
                    }
                }
            } catch (IOException e) {
                // stream closed on exit
            }
        }, "tck-crosscell-log");
        reader.setDaemon(true);
        reader.start();
    }

    private String awaitBoot() {
        Instant deadline = Instant.now().plus(BOOT_TIMEOUT);
        synchronized (log) {
            while (true) {
                boolean done = log.stream().anyMatch(l -> l.contains("Done ("));
                boolean up = log.stream().anyMatch(l -> l.contains("[VINE] phase SERVER_UP"));
                if (done && up) {
                    return null;
                }
                if (!server.isAlive()) {
                    return "server exited during boot (exit " + server.exitValue() + ")";
                }
                if (Instant.now().isAfter(deadline)) {
                    return "boot markers not seen within " + BOOT_TIMEOUT.toSeconds() + "s";
                }
                try {
                    log.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted";
                }
            }
        }
    }

    private String await(int from, java.util.function.Predicate<String> match, String what) {
        Instant deadline = Instant.now().plus(ASSERT_TIMEOUT);
        synchronized (log) {
            while (true) {
                for (int i = Math.max(from, 0); i < log.size(); i++) {
                    if (match.test(log.get(i))) {
                        return null;
                    }
                }
                if (!server.isAlive()) {
                    return "server exited while awaiting " + what;
                }
                if (Instant.now().isAfter(deadline)) {
                    return "timed out awaiting " + what;
                }
                try {
                    log.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted while awaiting " + what;
                }
            }
        }
    }

    private void send(String command) throws IOException {
        stdin.write(command);
        stdin.newLine();
        stdin.flush();
    }

    private void stopServer() throws IOException {
        if (server == null || !server.isAlive()) {
            return;
        }
        send("stop");
        try {
            if (!server.waitFor(STOP_GRACE.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                killTree();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree();
        }
    }

    private void killTree() {
        try {
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                new ProcessBuilder("taskkill", "/PID", String.valueOf(server.pid()), "/T", "/F")
                        .start().waitFor();
            } else {
                server.destroyForcibly();
            }
        } catch (Exception e) {
            server.destroyForcibly();
        }
    }
}
