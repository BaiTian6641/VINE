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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import java.util.stream.Stream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Headless TCK scenario runner (sub-21 Stage B): discovers the testmod's
 * scenario JSON resources, boots one driver cell's dedicated server as a child
 * Gradle process, drives the scenario steps over the server console (stdin)
 * and the boot log (assertions), and exits 0 only when every scenario passes.
 *
 * <p><b>Probe design notes (hard-won):</b> vanilla {@code execute if block} is
 * unusable from the dedicated console — the level-less console source makes
 * {@code BlockPosArgument} NPE fatally, killing the server thread. World
 * assertions therefore use setblock outcome discriminators: "Changed the block
 * at" proves the slot held something else; "Could not set the block" proves it
 * held the same state. Every post-send assertion scans only log lines appended
 * after the send (cursor-based), so stale history can never satisfy an assert;
 * history is cleared on each (re)boot so boot-marker awaits see only the
 * current server.
 *
 * <p>Step executors: {@code AssertTrace} (ordered subsequence, full current
 * boot history), {@code RunCommand}, {@code PlaceBlock}, {@code AssertData}
 * (world probes; registry-java via the testmod's post-freeze probe line;
 * registry-json as a resource-side twin check until sub-02 Stage F lands
 * engine-side JSON loading), {@code SendPacket} (testmod's
 * {@code vine_test tck_echo} console command + {@code vine.tck.loopback}
 * transport — see TckLoopbackDriver), {@code SaveReloadWorld} (graceful stop +
 * relaunch into the same run dir).
 *
 * <p>Process-control invariants mirror BootSmoke: child runs {@code --no-daemon}
 * with its own {@code --project-cache-dir}; jar tasks are pinned into the same
 * child invocation as {@code runServer} so the server can never ride a stale
 * testmod jar (the Windows shared-build race observed in sub-07's wave).
 */
public final class ScenarioRunner {

    private static final int EXIT_USAGE = 2;
    private static final int EXIT_FAIL = 1;
    private static final Duration ASSERT_TIMEOUT = Duration.ofSeconds(90);

    /**
     * The pinned world seed: any fixed value does, what matters is that it never
     * changes between runs or cells. Vanilla accepts a string seed and hashes it.
     */
    private static final String SEED = "vine-tck-determinism";

    /** Vanilla's game-time answer: {@code The time is <N>} (also the tick barrier's signal). */
    private static final java.util.regex.Pattern GAMETIME =
        java.util.regex.Pattern.compile("The time is (\\d+)");
    private static final Duration STOP_GRACE = Duration.ofSeconds(90);

    /** One scenario's outcome for the results journal (sub-21 Stage D). */
    private record Outcome(String scenario, String status, String failure, long durationMs) {
    }

    /** Quarantined scenarios — failures here are reported, not gated (sub-21 Stage D). */
    private final java.util.Set<String> quarantined = new java.util.HashSet<>();
    /** This run's outcomes, journaled at the end. */
    private final List<Outcome> outcomes = new java.util.ArrayList<>();

    /** Loads the report renderer's quarantine decision ({@code <tck-build>/results/quarantine.json}). */
    private void loadQuarantine() {
        Path decision = projectCacheDir.resolveSibling("results").resolve("quarantine.json");
        if (!Files.exists(decision)) {
            return;
        }
        try {
            var root = com.google.gson.JsonParser.parseString(
                Files.readString(decision, StandardCharsets.UTF_8)).getAsJsonObject();
            if (root.has("scenarios")) {
                for (var element : root.getAsJsonArray("scenarios")) {
                    quarantined.add(element.getAsString());
                }
            }
            if (!quarantined.isEmpty()) {
                System.out.println("[TCK] " + quarantined.size()
                    + " scenario(s) quarantined — failures reported, gate exempt (see QUARANTINE.md)");
            }
        } catch (Exception e) {
            System.out.println("[TCK] quarantine decision unreadable, treated as empty: " + e);
        }
    }

    private final String cell;
    private int bootCount;
    private final Path rootDir;
    private final String gradleTask;

    private final Path projectCacheDir;
    private final Path scenariosDir;
    private final Duration timeout;

    private final List<String> log = new ArrayList<>();
    private Process server;
    private BufferedWriter stdin;
    private int[] lastPlacePos = {0, 0, 0};
    private String lastPlaceBlock = "minecraft:stone";
    private final java.util.Set<Long> forceloaded = new java.util.HashSet<>();
    private boolean firstBoot = true;

    private ScenarioRunner(String cell, Path rootDir, String gradleTask,
            Path projectCacheDir, Path scenariosDir, Duration timeout) {
        this.cell = cell;
        this.rootDir = rootDir;
        this.gradleTask = gradleTask;
        this.projectCacheDir = projectCacheDir;
        this.scenariosDir = scenariosDir;
        this.timeout = timeout;
    }

    public static void main(String[] args) throws Exception {
        String cell = null;
        Path rootDir = null;
        String gradleTask = null;
        Path projectCacheDir = null;
        Path scenariosDir = null;
        Duration timeout = Duration.ofMinutes(15);
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--cell" -> cell = args[++i];
                case "--root-dir" -> rootDir = Path.of(args[++i]);
                case "--gradle-task" -> gradleTask = args[++i];
                case "--project-cache-dir" -> projectCacheDir = Path.of(args[++i]);
                case "--scenarios-dir" -> scenariosDir = Path.of(args[++i]);
                case "--timeout-seconds" -> timeout = Duration.ofSeconds(Long.parseLong(args[++i]));
                default -> {
                    System.err.println("[TCK] unknown argument: " + args[i]);
                    System.exit(EXIT_USAGE);
                }
            }
        }
        if (cell == null || rootDir == null || gradleTask == null
                || projectCacheDir == null || scenariosDir == null) {
            System.err.println("""
                    usage: ScenarioRunner --cell <cell> --root-dir <path> --gradle-task <task> \\
                                          --project-cache-dir <path> --scenarios-dir <path> \\
                                          [--timeout-seconds N]""");
            System.exit(EXIT_USAGE);
        }
        System.exit(new ScenarioRunner(cell, rootDir, gradleTask, projectCacheDir, scenariosDir, timeout).run());
    }

    private int run() throws Exception {
        List<Path> files;
        try (Stream<Path> s = Files.walk(scenariosDir)) {
            files = s.filter(p -> p.toString().endsWith(".json"))
                    .filter(p -> p.getParent() != null
                            && p.getParent().getFileName().toString().equals("scenarios"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        if (files.isEmpty()) {
            System.out.println("[TCK] " + cell + ": no scenarios found under " + scenariosDir);
            return EXIT_FAIL;
        }
        System.out.println("[TCK] " + cell + ": " + files.size() + " scenarios from " + scenariosDir);

        startServer();

        int failed = 0;
        outcomes.clear();
        loadQuarantine();
        for (Path file : files) {
            String id = file.getFileName().toString().replaceFirst("\\.json$", "");
            long startedAt = System.nanoTime();
            String failure = runScenario(file);
            outcomes.add(new Outcome(id, failure == null ? "PASS" : "FAIL", failure,
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)));
            if (failure == null) {
                System.out.println("[TCK] scenario " + id + ": PASS");
            } else if (quarantined.contains(id)) {
                // Quarantine is a policy exemption, not a silent pass: the
                // failure is printed and journaled, but it does not gate.
                System.out.println("[TCK] scenario " + id + ": QUARANTINED — " + failure);
            } else {
                failed++;
                System.out.println("[TCK] scenario " + id + ": FAIL — " + failure);
            }
        }
        stopServer();
        // Persist the full child-stdout capture: the authoritative evidence
        // stream (System.out testmod prints never reach latest.log).
        Path capture = projectCacheDir.resolveSibling("tck-capture-" + cell + ".log");
        try {
            synchronized (log) {
                Files.writeString(capture, String.join(System.lineSeparator(), log));
            }
        } catch (IOException e) {
            System.out.println("[TCK] could not persist capture: " + e);
        }
        System.out.println("[TCK] " + cell + ": " + (files.size() - failed) + "/" + files.size()
                + " scenarios passed");
        writeJournal(cell, files.size(), failed);
        return failed == 0 ? 0 : EXIT_FAIL;
    }

    /**
     * Pins the dev server's world seed before the first boot of this runner
     * (sub-21 Stage E determinism helpers). The world is wiped at the same moment,
     * so the world the server generates afterwards is a function of this seed —
     * a fixed point that makes terrain-dependent observations comparable between
     * runs and cells.
     *
     * <p>Only {@code level-seed} is touched; every other key the loader or a
     * previous run wrote is preserved verbatim. An already-generated world keeps
     * its own seed — substituting a new one would be a lie about a world that
     * exists — so the pin applies to the world this boot is about to create.
     */
    private void pinLevelSeed(Path runDir) {
        try {
            Files.createDirectories(runDir);
            Path properties = runDir.resolve("server.properties");
            List<String> lines = Files.exists(properties)
                ? new ArrayList<>(Files.readAllLines(properties, StandardCharsets.UTF_8))
                : new ArrayList<>();
            boolean replaced = false;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith("level-seed=")) {
                    lines.set(i, "level-seed=" + SEED);
                    replaced = true;
                }
            }
            if (!replaced) {
                lines.add("level-seed=" + SEED);
            }
            Files.write(properties, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[TCK] could not pin the level seed: " + e);
        }
    }

    private String runScenario(Path file) {
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            return "unparseable scenario: " + e.getMessage();
        }
        String id = root.has("id") ? root.get("id").getAsString() : file.toString();
        try {
            for (var element : root.getAsJsonArray("steps")) {
                JsonObject step = element.getAsJsonObject();
                String type = step.get("type").getAsString();
                String failure = switch (type) {
                    case "AssertTrace" -> assertTrace(step);
                    case "RunCommand" -> runCommand(step);
                    case "PlaceBlock" -> placeBlock(step);
                    case "AssertData" -> assertData(step);
                    case "SendPacket" -> sendPacket(step);
                    case "SaveReloadWorld" -> saveReloadWorld();
                    case "AdvanceTicks" -> advanceTicks(step);
                    case "WriteFile" -> writeFile(step);
                    default -> "unknown step type: " + type;
                };
                if (failure != null) {
                    dumpConsoleTail();
                    return "step " + type + " in " + id + ": " + failure;
                }
            }
            return null;
        } catch (Exception e) {
            return "unexpected: " + e;
        }
    }

    // ------------------------------------------------------------------
    // Step executors
    // ------------------------------------------------------------------

    private String assertTrace(JsonObject step) {
        List<String> expect = new ArrayList<>();
        for (var e : step.getAsJsonArray("expect")) {
            expect.add(e.getAsString());
        }
        String trace = step.get("trace").getAsString();
        List<String> needles = switch (trace) {
            case "phases" -> expect.stream().map(p -> "[VINE] phase " + p).toList();
            case "echo" -> expect.stream().map(t -> "[vine-testmod] echo: " + t).toList();
            default -> expect;
        };
        // One ordered-subsequence poll over the whole current-boot history:
        // boot-emitted traces (phases) precede any scenario cursor, and
        // post-command traces arrive with console latency — polling the full
        // sequence until it appears in order handles both.
        Instant deadline = Instant.now().plus(ASSERT_TIMEOUT);

        synchronized (log) {
            while (true) {
                // Ordered subsequence over the log *characters*, not lines: two
                // needles that describe one output line (a value and its verdict)
                // must both match, which line-granular cursors silently reject.
                int line = 0;
                int col = 0;
                boolean all = true;
                for (String needle : needles) {
                    boolean found = false;
                    while (line < log.size()) {
                        int at = log.get(line).indexOf(needle, col);
                        if (at >= 0) {
                            col = at + needle.length();
                            found = true;
                            break;
                        }
                        line++;
                        col = 0;
                    }
                    if (!found) {
                        all = false;
                        break;
                    }
                }
                if (all) {
                    return null;
                }
                if (!server.isAlive()) {
                    return "trace not observed in order (server exited); missing around '" + needles + "'";
                }
                if (Instant.now().isAfter(deadline)) {
                    return "trace not observed in order within timeout: " + needles;
                }
                try {
                    log.wait(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "interrupted while awaiting trace";
                }
            }
        }
    }

    private String runCommand(JsonObject step) throws IOException {
        send(step.get("command").getAsString());
        // Assertions follow as their own steps; nothing to await here.
        return null;
    }

    /**
     * The tick-barrier step (sub-21 Stage E determinism helpers): freeze the
     * server's tick loop, step it exactly {@code ticks} times, prove the world's
     * game time advanced by exactly that many ticks, then unfreeze.
     *
     * <p>Freezing is what makes the step worth having: a scenario that needs a
     * fixed number of ticks to elapse between two observations cannot get one from
     * a free-running loop, and stepping a frozen loop is the only mechanism
     * vanilla offers that is exact rather than "at least". The unfreeze at the end
     * keeps the step side-effect-free for the scenarios that follow it in the same
     * boot; a scenario that wants a frozen world for its whole body says so with an
     * explicit {@code RunCommand} of {@code tick freeze}.
     *
     * <p>Every check is an observable effect of the server, never a phrase from its
     * output: a frozen loop is proven by game time standing still across a poll,
     * and a step is proven by the exact delta it produced. The step therefore
     * cannot pass by matching a message some loader decided to reword.
     */
    private String advanceTicks(JsonObject step) throws IOException {
        if (!step.has("ticks")) {
            return "AdvanceTicks needs a \"ticks\" count";
        }
        int ticks = step.get("ticks").getAsInt();
        if (ticks <= 0) {
            return "AdvanceTicks needs a positive tick count, got " + ticks;
        }
        send("tick freeze");
        int frozenAt = awaitGametime();
        if (frozenAt < 0) {
            send("tick unfreeze");
            return "could not read game time while freezing the tick loop";
        }
        sleepFor(250);
        int stillFrozen = awaitGametime();
        if (stillFrozen < 0) {
            send("tick unfreeze");
            return "could not read game time a second time while frozen";
        }
        if (stillFrozen != frozenAt) {
            send("tick unfreeze");
            return "the tick loop kept advancing after /tick freeze (game time " + frozenAt + " -> "
                + stillFrozen + "), so a tick-exact barrier cannot be driven on this cell";
        }
        send("tick step " + ticks);
        long deadline = System.nanoTime() + ASSERT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            int now = awaitGametime();
            if (now < 0) {
                continue;
            }
            int advanced = now - stillFrozen;
            if (advanced == ticks) {
                send("tick unfreeze");
                return null;
            }
            if (advanced > ticks) {
                send("tick unfreeze");
                return "game time advanced " + advanced + " ticks, expected exactly " + ticks;
            }
        }
        send("tick unfreeze");
        return "game time did not advance " + ticks + " ticks within " + ASSERT_TIMEOUT;
    }

    /** Sends {@code time query gametime} and returns the answer that follows it. */
    private int awaitGametime() throws IOException {
        int cursor;
        synchronized (log) {
            cursor = log.size();
        }
        send("time query gametime");
        long deadline = System.nanoTime() + ASSERT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            int value = gametimeAfter(cursor);
            if (value >= 0) {
                return value;
            }
            sleepFor(50);
        }
        return -1;
    }

    /** The first {@code The time is N} printed at or after {@code cursor}, or -1. */
    private int gametimeAfter(int cursor) {
        synchronized (log) {
            for (int i = cursor; i < log.size(); i++) {
                var matcher = GAMETIME.matcher(log.get(i));
                if (matcher.find()) {
                    try {
                        return Integer.parseInt(matcher.group(1));
                    } catch (NumberFormatException e) {
                        return -1;
                    }
                }
            }
        }
        return -1;
    }

    private static void sleepFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String placeBlock(JsonObject step) throws IOException {
        var pos = step.getAsJsonArray("pos");
        lastPlacePos = new int[] {pos.get(0).getAsInt(), pos.get(1).getAsInt(), pos.get(2).getAsInt()};
        lastPlaceBlock = step.get("block").getAsString();
        return probeSetblock(lastPlacePos, lastPlaceBlock, true, "setblock change confirmation");
    }

    /**
     * One setblock probe with forceload and a single unloaded-chunk retry.
     *
     * <p>A fresh (or freshly rebooted) world keeps no chunks loaded at console
     * time — vanilla rejects the setblock with "That position is not loaded".
     * The target chunk is force-loaded first; re-adding an already-forced chunk
     * prints nothing, so forceload runs once per boot per chunk (deduped), and
     * on an unloaded-chunk outcome the dedup entry is dropped and both steps
     * re-issued once.
     */
    private String probeSetblock(int[] pos, String block, boolean passOnChanged, String what)
            throws IOException {
        long chunkKey = (pos[0] & 0xFFFFFFFFL) | (pos[2] & 0xFFFFFFFFL) << 32;
        for (int attempt = 0; attempt < 2; attempt++) {
            if (forceloaded.add(chunkKey)) {
                int fl = mark();
                send("forceload add " + pos[0] + " " + pos[2]);
                // Non-fatal, short wait: forceload state persists in the world
                // across reboots, so an already-forced chunk prints nothing.
                // The setblock outcome below is the real discriminator; an
                // actually-unloaded chunk returns "That position is not loaded"
                // and takes the retry path.
                await(fl, line -> line.contains("Marked chunk") || line.contains("No chunks were marked"),
                    "forceload confirmation", java.time.Duration.ofSeconds(15));
            }
            int from = mark();
            send("setblock " + pos[0] + " " + pos[1] + " " + pos[2] + " " + block);
            String outcome = awaitSetblock(from, passOnChanged, what);
            if (outcome == null || !outcome.startsWith("RETRY:")) {
                return outcome;
            }
            forceloaded.remove(chunkKey);
        }
        return "timed out awaiting " + what + " (chunk stayed unloaded across retry)";
    }

    private String assertData(JsonObject step) throws IOException {
        String surface = step.get("surface").getAsString();
        String ref = step.get("ref").getAsString();
        String expect = step.get("expect").getAsString();
        switch (surface) {
            case "world" -> {
                // Console-safe probes: `execute if block` NPEs fatally from the
                // dedicated console (level-less source), so presence is proven
                // by setblock outcomes instead. Every scenario ends slot-empty:
                // present removes via "Changed"; absence is air→air "Could not set".
                if ("present".equals(expect)) {
                    return probeSetblock(lastPlacePos, "air", true,
                            "block present (removal changed the slot)");
                }
                if ("absent-after-break".equals(expect)) {
                    return probeSetblock(lastPlacePos, "air", false,
                            "slot absent (air→air rejected)");
                }
                return "unknown world expect: " + expect;
            }
            case "registry-java" -> {
                // The probe line prints at REGISTRIES_FROZEN during boot —
                // before any scenario cursor. History is cleared per boot, so
                // a from-zero scan is current-boot-only by construction.
                return await(0, line -> line.contains("marker holder resolved post-freeze id=" + ref),
                        "registry holder probe for " + ref);
            }
            case "registry-json" -> {
                // Resource-side twin check (engine-side JSON loading is sub-02 Stage F):
                // vinetest:example maps to resources/data/vinetest/vinetest/marker/example.json.
                String[] parts = ref.split(":", 2);
                Path twin = scenariosDir.getParent().getParent()
                        .resolve("data/" + parts[0] + "/" + parts[0] + "/marker/" + parts[1] + ".json")
                        .normalize();
                if (!Files.exists(twin)) {
                    return "json descriptor twin missing: " + twin;
                }
                return null;
            }
            default -> {
                return "unknown surface: " + surface;
            }
        }
    }

    private String sendPacket(JsonObject step) throws IOException {
        JsonObject payload = step.getAsJsonObject("payload");
        // The channel is pinned by the scenario contract; the payload rides the
        // testmod's console drive command through the real C2S path.
        send("vine_test tck_echo " + payload.get("number").getAsInt()
                + " " + payload.get("text").getAsString());
        return null;
    }

    /**
     * Writes a UTF-8 fixture file under the cell's run dir (datapack overrides,
     * config files). Paths are run-dir-relative; escapes are rejected.
     */
    private String writeFile(JsonObject step) throws IOException {
        String path = step.get("path").getAsString();
        String content = step.get("content").getAsString();
        Path base = rootDir
            .resolve(gradleTask.substring(1, gradleTask.lastIndexOf(':')).replace(':', '/'))
            .resolve("run").normalize();
        Path target = base.resolve(path).normalize();
        if (!target.startsWith(base)) {
            return "WriteFile path escapes the run dir: " + path;
        }
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return null;
    }

    private String saveReloadWorld() throws Exception {
        stopServer();
        startServer();
        return null;
    }

    // ------------------------------------------------------------------
    // Log watching (cursor-based: asserts only see lines appended after mark())
    // ------------------------------------------------------------------

    private int mark() {
        synchronized (log) {
            return log.size();
        }
    }

    /**
     * Appends this run's outcomes to {@code results/<cell>.jsonl} (sub-21 Stage D):
     * the journal the report renderer reads for the matrix, history and
     * quarantine decisions. Quarantined scenarios are recorded but never gate the
     * build — the ledger stays visible either way.
     */
    private void writeJournal(String cell, int total, int failed) {
        Path resultsDir = projectCacheDir.resolveSibling("results");
        try {
            Files.createDirectories(resultsDir);
            StringBuilder journal = new StringBuilder();
            String runId = java.time.Instant.now().toString();
            for (Outcome outcome : outcomes) {
                boolean isQuarantined = quarantined.contains(outcome.scenario());
                journal.append("{\"run\":\"").append(runId)
                    .append("\",\"cell\":\"").append(cell)
                    .append("\",\"scenario\":\"").append(outcome.scenario())
                    .append("\",\"status\":\"").append(isQuarantined ? "QUARANTINED" : outcome.status())
                    .append("\",\"durationMs\":").append(outcome.durationMs())
                    .append(",\"failure\":").append(jsonString(outcome.failure()))
                    .append("}\n");
            }
            Path target = resultsDir.resolve(cell + ".jsonl");
            Files.writeString(target, journal.toString(), StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            // Failure artifact: an excerpt bundle the report links to.
            for (Outcome outcome : outcomes) {
                if (!"FAIL".equals(outcome.status()) || isQuarantinedSkip(outcome)) {
                    continue;
                }
                Path artifact = resultsDir.resolve("artifact-" + cell + "-" + outcome.scenario() + ".txt");
                Files.writeString(artifact, "scenario: " + outcome.scenario() + System.lineSeparator()
                    + "cell: " + cell + System.lineSeparator()
                    + "failure: " + outcome.failure() + System.lineSeparator()
                    + System.lineSeparator() + lastConsoleLines(40));
            }
        } catch (IOException e) {
            System.out.println("[TCK] could not write results journal: " + e);
        }
    }

    private boolean isQuarantinedSkip(Outcome outcome) {
        return quarantined.contains(outcome.scenario());
    }

    private String lastConsoleLines(int count) {
        synchronized (log) {
            int from = Math.max(0, log.size() - count);
            return String.join(System.lineSeparator(), log.subList(from, log.size()));
        }
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Prints the tail of the captured console so a failure carries its evidence. */
    private void dumpConsoleTail() {
        synchronized (log) {
            int from = Math.max(0, log.size() - 30);
            System.out.println("[TCK-DUMP] last " + (log.size() - from) + " console lines:");
            for (int i = from; i < log.size(); i++) {
                System.out.println("[TCK-DUMP] " + log.get(i));
            }
        }
    }

    /** @return null when a matching line appears after {@code from} in time. */
    private String await(int from, Predicate<String> match, String what) {
        return await(from, match, what, ASSERT_TIMEOUT);
    }

    /** Timed variant of {@link #await} for best-effort waits. */
    private String await(int from, Predicate<String> match, String what, java.time.Duration window) {
        Instant deadline = Instant.now().plus(window);
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

    /**
     * Awaits a setblock outcome discriminator. {@code passOnChanged=true}:
     * "Changed the block at" passes, "Could not set the block" fails (presence
     * probe). {@code passOnChanged=false}: inverted — "Could not set" passes
     * (slot already held the target state, e.g. air→air absence probe) and
     * "Changed" fails. Lines mentioning a thrown exception never match (an
     * earlier wave false-passed on the error echo of its own command text).
     */
    private String awaitSetblock(int from, boolean passOnChanged, String what) {
        Instant deadline = Instant.now().plus(ASSERT_TIMEOUT);
        synchronized (log) {
            while (true) {
                for (int i = Math.max(from, 0); i < log.size(); i++) {
                    String line = log.get(i);
                    if (line.contains("threw an exception")) {
                        continue;
                    }
                    if (line.contains("Changed the block at")) {
                        return passOnChanged ? null
                                : what + ": setblock CHANGED the slot — expected it already matching";
                    }
                    if (line.contains("Could not set the block")) {
                        return passOnChanged
                                ? what + ": setblock reported 'Could not set the block'"
                                : null;
                    }
                    if (line.contains("That position is not loaded")) {
                        // Retryable: a post-reboot world may not have the chunk
                        // loaded yet even after forceload. Callers re-forceload
                        // and re-send once (see probeSetblock).
                        return "RETRY:" + what;
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

    // ------------------------------------------------------------------
    // Server process control (BootSmoke invariants: no daemon, isolated
    // project cache, stdin-driven console, tree-kill fallback).
    // ------------------------------------------------------------------

    private void startServer() throws IOException {
        // Fresh history per boot: boot-marker and probe awaits must only ever
        // observe the CURRENT server, never a previous incarnation.
        synchronized (log) {
            if (!log.isEmpty()) {
                Path prev = projectCacheDir.resolveSibling(
                    "tck-capture-" + cell + ".boot" + bootCount + ".log");
                try {
                    Files.writeString(prev, String.join(System.lineSeparator(), log));
                } catch (IOException e) {
                    System.out.println("[TCK] could not persist boot capture: " + e);
                }
            }
            bootCount++;
            log.clear();
        }
        forceloaded.clear();
        // Deterministic state: wipe the dev server's saved world so a crashed
        // prior run can never leak block state into this one — but ONLY on the
        // first boot of this runner instance. save_reload's deliberate relaunch
        // must observe the saved world, never a wiped one.
        if (firstBoot) {
            firstBoot = false;
            // Scenario-owned run-dir fixtures (id-map flags/policy) must not leak
            // between runs, exactly like world state: determinism first.
            Path runDir = rootDir
                .resolve(gradleTask.substring(1, gradleTask.lastIndexOf(':')).replace(':', '/'))
                .resolve("run");
            try (Stream<Path> fixtures = Files.list(runDir)) {
                fixtures.filter(p -> p.getFileName().toString().startsWith("vine_tck_"))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            // best-effort
                        }
                    });
            } catch (IOException e) {
                // run dir may not exist before the first boot; the scenario writes what it needs
            }
            pinLevelSeed(runDir);
            String projectPath = gradleTask.substring(1, gradleTask.lastIndexOf(':')).replace(':', '/');
            Path world = rootDir.resolve(projectPath).resolve("run").resolve("world");
            if (Files.exists(world)) {
                try (Stream<Path> w = Files.walk(world)) {
                    w.sorted(Comparator.reverseOrder()).forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            // best-effort; a locked file fails the PlaceBlock probe loudly
                        }
                    });
                } catch (IOException e) {
                    // best-effort wipe
                }
            }
        }
        List<String> cmd = new ArrayList<>();
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
        // Ephemeral server port (sub-21 Stage E): 25565 is a shared resource — the
        // developer's own dev server, another project's, another sweep's — so a
        // scenario run asks for a free port of its own instead of competing for the
        // default one. The runner talks to the server through stdin, so the port
        // never has to be reachable from outside this process.
        // Port 0, not a probed free port: the OS assigns it at bind time, so
        // nothing can take it between "we asked" and "the server binds". The first
        // version of this probed a port and closed the probe socket, and a busy
        // machine handed that port to something else in the gap — the sweep then
        // died with FAILED TO BIND TO PORT and every following boot failed the same
        // way. The runner talks over stdin, so the number never matters.
        cmd.add("-Pvine.tck.port=0");
        // Stale-jar pin (sub-07 wave finding): the server must run the jars
        // built by THIS invocation, never a cached older testmod.
        cmd.addAll(List.of(":vine-api:jar", ":vine-core:jar", ":vine-spi:jar", ":vine-testmod:jar"));
        cmd.add(gradleTask);

        server = new ProcessBuilder(cmd).directory(rootDir.toFile()).redirectErrorStream(true).start();
        stdin = new BufferedWriter(new OutputStreamWriter(server.getOutputStream(), StandardCharsets.UTF_8));
        Thread reader = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(server.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    synchronized (log) {
                        log.add(line);
                        if (log.size() > 60_000) {
                            log.subList(0, 30_000).clear();
                        }
                        log.notifyAll();
                    }
                }
            } catch (IOException e) {
                // stream closed on exit
            }
        }, "tck-scenario-log");
        reader.setDaemon(true);
        reader.start();

        String failure = awaitBoot();
        if (failure != null) {
            throw new IllegalStateException("server boot failed: " + failure);
        }
    }

    private String awaitBoot() {
        Instant deadline = Instant.now().plus(timeout);
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
                    return "boot markers not seen within " + timeout.toSeconds() + "s";
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
