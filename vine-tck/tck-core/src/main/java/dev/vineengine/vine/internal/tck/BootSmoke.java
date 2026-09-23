package dev.vineengine.vine.internal.tck;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Headless dedicated-server boot smoke for one driver cell (sub-21 Stage A).
 *
 * <p>Launches the cell's dev {@code runServer} configuration as a child
 * Gradle process, watches the merged server log for the boot markers, then
 * stops the server (graceful {@code stop} first, tree kill as fallback) and
 * reports PASS/FAIL. Loader-free and cell-agnostic by construction: the cell
 * identity and the Gradle task to launch arrive as arguments, so adding a
 * cell is wiring, never code (sub-21 §4 — no hardcoded cell counts, no
 * version-string parsing, §5.12).
 *
 * <p>Marker contract (sub-21 Stage A): the vanilla dedicated-server line
 * {@code Done (...)! For help, type "help"} plus the driver entrypoint line
 * {@code VINE driver <cell> alive}. When {@code --assert-phase-markers} is
 * passed, the five sub-01 phase-transition lines
 * ({@code [VINE] phase VINE_BOOT … SERVER_UP}, in order) are asserted too;
 * that toggle activates once sub-18 Stage B wires drivers into the engine
 * bootstrap.
 *
 * <p>Process-control invariant: the child build runs {@code --no-daemon}
 * with its own {@code --project-cache-dir} so (a) it never blocks on the
 * outer build's project lock and (b) the server JVM is a descendant of the
 * process we spawned, making a tree kill a reliable stop.
 */
public final class BootSmoke {

    /** The sub-01 Stage A phase machine, in transition order. */
    private static final List<String> EXPECTED_PHASES = List.of(
            "VINE_BOOT", "REGISTRIES_OPEN", "REGISTRIES_FROZEN", "WORLD_LOAD", "SERVER_UP");

    private static final int TAIL_LINES = 120;
    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAIL = 1;

    private record Spec(
            String cell,
            Path rootDir,
            String gradleTask,
            Path projectCacheDir,
            Duration timeout,
            Duration stopGrace,
            boolean assertPhases) {}

    private BootSmoke() {}

    public static void main(String[] args) throws Exception {
        Spec spec;
        try {
            spec = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("[TCK] " + e.getMessage());
            usage();
            System.exit(EXIT_USAGE);
            return; // unreachable, satisfies flow analysis
        }
        System.exit(run(spec));
    }

    private static void usage() {
        System.err.println("""
                usage: BootSmoke --cell <cell> --root-dir <path> --gradle-task <task> \\
                                 --project-cache-dir <path> [--timeout-seconds N] \\
                                 [--stop-grace-seconds N] [--assert-phase-markers]""");
    }

    private static Spec parse(String[] args) {
        String cell = null;
        Path rootDir = null;
        String gradleTask = null;
        Path projectCacheDir = null;
        Duration timeout = Duration.ofMinutes(10);
        Duration stopGrace = Duration.ofSeconds(90);
        boolean assertPhases = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--cell" -> cell = next(args, ++i, "--cell");
                case "--root-dir" -> rootDir = Path.of(next(args, ++i, "--root-dir"));
                case "--gradle-task" -> gradleTask = next(args, ++i, "--gradle-task");
                case "--project-cache-dir" -> projectCacheDir = Path.of(next(args, ++i, "--project-cache-dir"));
                case "--timeout-seconds" -> timeout = Duration.ofSeconds(Long.parseLong(next(args, ++i, "--timeout-seconds")));
                case "--stop-grace-seconds" -> stopGrace = Duration.ofSeconds(Long.parseLong(next(args, ++i, "--stop-grace-seconds")));
                case "--assert-phase-markers" -> assertPhases = true;
                default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (cell == null || rootDir == null || gradleTask == null || projectCacheDir == null) {
            throw new IllegalArgumentException("--cell, --root-dir, --gradle-task and --project-cache-dir are required");
        }
        return new Spec(cell, rootDir, gradleTask, projectCacheDir, timeout, stopGrace, assertPhases);
    }

    private static String next(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException(flag + " requires a value");
        }
        return args[i];
    }

    private static int run(Spec spec) throws Exception {
        log("boot smoke " + spec.cell() + ": launching " + spec.gradleTask()
                + " (timeout " + spec.timeout().toSeconds() + "s"
                + (spec.assertPhases() ? ", asserting [VINE] phase markers" : "") + ")");
        Instant started = Instant.now();

        Process process = new ProcessBuilder(command(spec))
                .directory(spec.rootDir().toFile())
                .redirectErrorStream(true)
                .start();

        LogWatch watch = new LogWatch(spec.cell());
        Thread reader = new Thread(() -> readLines(process, watch), "tck-bootsmoke-log");
        reader.setDaemon(true);
        reader.start();

        String failure = awaitBoot(process, watch, spec);
        if (failure != null) {
            killTree(process);
            report(spec, watch, started, false, failure);
            return EXIT_FAIL;
        }

        Instant booted = Instant.now();
        String stopNote = stop(process, spec.stopGrace());
        report(spec, watch, started, true,
                "booted in " + millis(started, booted) + "ms; " + stopNote);
        return 0;
    }

    private static List<String> command(Spec spec) {
        List<String> cmd = new ArrayList<>();
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("gradlew.bat");
        } else {
            cmd.add("./gradlew");
        }
        // No daemon: the server JVM must stay inside our process tree so the
        // fallback kill is reliable. Plain console: parseable, CI-friendly.
        cmd.add("--no-daemon");
        cmd.add("--console=plain");
        // Isolated project cache: never contends with an outer build's lock.
        cmd.add("--project-cache-dir");
        cmd.add(spec.projectCacheDir().toString());
        cmd.add(spec.gradleTask());
        return cmd;
    }

    private static void readLines(Process process, LogWatch watch) {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                watch.onLine(line);
            }
        } catch (IOException e) {
            watch.onLine("[TCK] log stream closed: " + e);
        }
    }

    /** @return null once every required marker is seen, else the failure reason. */
    private static String awaitBoot(Process process, LogWatch watch, Spec spec) throws InterruptedException {
        Instant deadline = Instant.now().plus(spec.timeout());
        synchronized (watch) {
            while (true) {
                String missing = watch.missing(spec.assertPhases());
                if (missing == null) {
                    return null;
                }
                if (!process.isAlive()) {
                    return "server exited before boot completed (exit " + process.exitValue()
                            + "); missing: " + missing;
                }
                long remainingMs = Duration.between(Instant.now(), deadline).toMillis();
                if (remainingMs <= 0) {
                    return "boot markers not seen within " + spec.timeout().toSeconds()
                            + "s; missing: " + missing;
                }
                watch.wait(Math.min(remainingMs, 1000));
            }
        }
    }

    /** Graceful {@code stop} over stdin first; tree kill after the grace window. */
    private static String stop(Process process, Duration grace) throws InterruptedException {
        try {
            BufferedWriter out = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            out.write("stop");
            out.newLine();
            out.flush();
        } catch (IOException e) {
            log("could not send 'stop' (" + e + "); killing process tree");
            killTree(process);
            return "process tree killed (stop command undeliverable)";
        }
        if (process.waitFor(grace.toSeconds(), TimeUnit.SECONDS)) {
            return "server stopped gracefully (exit " + process.exitValue() + ")";
        }
        killTree(process);
        return "process tree killed after " + grace.toSeconds() + "s grace (no graceful stop)";
    }

    private static void killTree(Process process) throws InterruptedException {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(30, TimeUnit.SECONDS);
    }

    private static void report(Spec spec, LogWatch watch, Instant started, boolean pass, String detail) {
        log("==================================================");
        // ASCII-only: the runner JVM inherits the platform console encoding,
        // which mangles punctuation in captured CI logs on non-UTF-8 hosts.
        log("boot smoke " + spec.cell() + ": " + (pass ? "PASS" : "FAIL") + " - " + detail);
        synchronized (watch) {
            for (String marker : watch.seenMarkers()) {
                log("  seen: " + marker);
            }
            if (!pass) {
                log("---- last " + TAIL_LINES + " server log lines ----");
                for (String line : watch.tail()) {
                    System.out.println("[server-tail] " + line);
                }
                log("------------------------------------------------------");
            }
        }
        log("total wall time " + millis(started, Instant.now()) + "ms");
        log("==================================================");
    }

    private static long millis(Instant from, Instant to) {
        return Duration.between(from, to).toMillis();
    }

    private static void log(String message) {
        System.out.println("[TCK] " + message);
    }

    /** Line-by-line marker state. All access under {@code this} monitor. */
    private static final class LogWatch {
        private final String aliveMarker;
        private final Deque<String> tail = new ArrayDeque<>(TAIL_LINES + 1);
        private final List<String> phases = new ArrayList<>(EXPECTED_PHASES.size());
        private boolean doneSeen;
        private boolean aliveSeen;

        LogWatch(String cell) {
            this.aliveMarker = "VINE driver " + cell + " alive";
        }

        void onLine(String line) {
            synchronized (this) {
                System.out.println("[server] " + line);
                if (line.contains(aliveMarker)) {
                    aliveSeen = true;
                }
                // Vanilla dedicated-server ready line; stable since forever.
                if (line.contains("Done (") && line.contains("For help, type \"help\"")) {
                    doneSeen = true;
                }
                for (String phase : EXPECTED_PHASES) {
                    if (!phases.contains(phase) && line.contains("[VINE] phase " + phase)) {
                        phases.add(phase);
                    }
                }
                if (tail.size() == TAIL_LINES) {
                    tail.pollFirst();
                }
                tail.addLast(line);
                notifyAll();
            }
        }

        /** @return null if everything required was seen, else a human-readable gap list. */
        String missing(boolean assertPhases) {
            List<String> missing = new ArrayList<>();
            if (!doneSeen) {
                missing.add("vanilla \"Done (...)\" line");
            }
            if (!aliveSeen) {
                missing.add("'" + aliveMarker + "'");
            }
            if (assertPhases && !phases.equals(EXPECTED_PHASES)) {
                missing.add("[VINE] phase sequence " + EXPECTED_PHASES + " (saw " + phases + ")");
            }
            return missing.isEmpty() ? null : String.join(", ", missing);
        }

        List<String> seenMarkers() {
            List<String> seen = new ArrayList<>();
            if (doneSeen) {
                seen.add("Done (...)! For help, type \"help\"");
            }
            if (aliveSeen) {
                seen.add(aliveMarker);
            }
            if (!phases.isEmpty()) {
                seen.add("[VINE] phase sequence " + phases);
            }
            return seen;
        }

        Deque<String> tail() {
            return tail;
        }
    }
}
