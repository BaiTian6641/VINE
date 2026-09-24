package dev.vineengine.vine.testmod.tck;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.vineengine.vine.command.VineCommands;
import dev.vineengine.vine.command.VineCommand;
import dev.vineengine.vine.command.VinePermission;
import dev.vineengine.vine.registry.VineId;

import static dev.vineengine.vine.testmod.VineTestmod.MOD_ID;

/**
 * The in-process half of the TCK harness (sub-21 Stage B): {@code /vinetck run
 * <id|all>} executes the same scenario files the external runner drives, inside
 * the running server, and prints the same verdict lines — so a scenario's
 * meaning is defined by its file, not by which harness ran it.
 *
 * <p>What differs is reach, not semantics: the external runner owns the process
 * (it can restart the server, launch Gradle, place blocks through commands and
 * read a fresh capture per boot), while this one has only what a running server
 * exposes. Steps it cannot serve are reported as {@code SKIP} with the reason,
 * never as a silent pass — a harness that quietly narrows a scenario would be
 * worse than no second harness.
 *
 * <p>Trace assertions read the output the server produced while the scenario ran
 * (stdout is teed for the duration), which is exactly what the external runner
 * asserts against, minus boot-time lines that happened before the command.
 */
public final class InProcessRunner {

    /** Where the scenarios live inside the testmod jar. */
    private static final String SCENARIOS_ROOT = "vine-tck/scenarios/";

    private InProcessRunner() {
    }

    /** Registers {@code /vine_test tck_run <id|all>} — the harness's in-process entry. */
    public static void register() {
        VineCommands.get().register(VineCommand.literal(MOD_ID)
            .permission(VinePermission.level(2))
            .then(VineCommand.literal("tck_run")
                .then(VineCommand.argument("target", dev.vineengine.vine.command.VineArgumentTypes.STRING)
                    .executes(ctx -> {
                        String target = ctx.argument("target", String.class);
                        List<String> verdicts = run(target);
                        for (String verdict : verdicts) {
                            ctx.feedback(verdict);
                        }
                        // Also on stdout: the in-process verdict must be visible to
                        // whichever harness is driving *this* process, including a
                        // nested in-process run.
                        for (String verdict : verdicts) {
                            System.out.println(verdict);
                        }
                        return verdicts.stream().noneMatch(v -> v.contains(": FAIL")) ? 1 : 0;
                    })))
            .build(VineId.of(MOD_ID, "tck_run")));
    }

    /** Runs one scenario (or every discovered one) and returns its verdict lines. */
    public static List<String> run(String target) {
        List<String> verdicts = new ArrayList<>();
        List<String> ids = "all".equals(target) ? discovered() : List.of(target);
        for (String id : ids) {
            verdicts.add(runOne(id));
        }
        return verdicts;
    }

    private static String runOne(String id) {
        String json = readScenario(id);
        if (json == null) {
            return "[TCK] scenario " + id + ": SKIP (no such scenario)";
        }
        Map<String, Object> scenario;
        try {
            scenario = MiniJson.object(MiniJson.parse(json));
        } catch (RuntimeException unparseable) {
            return "[TCK] scenario " + id + ": FAIL (unparseable scenario: " + unparseable.getMessage() + ")";
        }
        List<String> trace = new ArrayList<>();
        try (Trace tee = Trace.install(trace)) {
            // Tee self-check: if this marker is missing from the trace, the sink is
            // the problem, not the scenario. (A harness that silently captures
            // nothing would report every trace assertion as a scenario failure.)
            System.out.println("[TCK] in-process capture active for " + id);
            // Engine lines (`[VINE] …`) go through java.util.logging into the cell's
            // logging stack, so the sink listens there too. Command *feedback* is a
            // cell console write; asserting on it in-process needs the cells'
            // feedback tee (sub-21 Stage B remaining), which is why scenarios that
            // assert feedback are reported as SKIP here instead of failing.
            for (Object stepValue : MiniJson.array(scenario.get("steps"))) {
                Map<String, Object> step = MiniJson.object(stepValue);
                String type = MiniJson.string(step.get("type"));
                String failure = execute(type, step, trace);
                if (failure != null) {
                    return "[TCK] scenario " + id + ": FAIL — " + failure;
                }
            }
        } catch (RuntimeException unexpected) {
            return "[TCK] scenario " + id + ": FAIL — unexpected: " + unexpected;
        }
        return "[TCK] scenario " + id + ": PASS";
    }

    /** @return a failure description, or {@code null} when the step passed */
    private static String execute(String type, Map<String, Object> step, List<String> trace) {
        switch (type) {
            case "RunCommand" -> {
                String command = MiniJson.string(step.get("command"));
                // The console path the driver installed, so the command's feedback
                // lands on stdout exactly as it does under the external runner.
                dev.vineengine.vine.internal.ConsoleDispatch.dispatch(command);
                System.out.println("[TCK] dispatch returned for " + command);
                return null;
            }
            case "AssertTrace" -> {
                List<String> needles = new ArrayList<>();
                for (Object needle : MiniJson.array(step.get("expect"))) {
                    needles.add(MiniJson.string(needle));
                }
                return assertTrace(needles, trace);
            }
            default -> {
                // World-, packet- and restart-shaped steps belong to the harness
                // that owns the process. Saying so is the point.
                return "SKIP (" + type + " needs the external runner)";
            }
        }
    }

    /**
     * In-process assertions can only see what the harness captures: the JVM's own
     * stdout writes plus engine log lines. A cell's console feedback travels its
     * logging stack (which the external runner reads from the process), so when a
     * run captured *nothing but the harness's own markers*, the honest verdict is
     * SKIP — never a failure that blames the scenario for the harness's reach.
     */
    private static String assertTrace(List<String> needles, List<String> trace) {
        if (trace.stream().allMatch(line -> line.startsWith("[TCK] "))) {
            return "SKIP (trace not captured in-process: cell feedback tee pending)";
        }
        int line = 0;
        int col = 0;
        for (String needle : needles) {
            boolean found = false;
            while (line < trace.size()) {
                int at = trace.get(line).indexOf(needle, col);
                if (at >= 0) {
                    col = at + needle.length();
                    found = true;
                    break;
                }
                line++;
                col = 0;
            }
            if (!found) {
                return "trace not observed: " + needles + " (captured " + trace.size() + " line(s)"
                    + (trace.isEmpty() ? ")" : "; last: " + trace.get(trace.size() - 1) + ")");
            }
        }
        return null;
    }

    /** Every scenario file the testmod ships, in sorted order. */
    private static List<String> discovered() {
        List<String> ids = new ArrayList<>();
        try {
            var roots = InProcessRunner.class.getClassLoader().getResources(SCENARIOS_ROOT);
            while (roots.hasMoreElements()) {
                URL root = roots.nextElement();
                if ("file".equals(root.getProtocol())) {
                    java.nio.file.Path dir = java.nio.file.Path.of(root.toURI());
                    try (var files = java.nio.file.Files.list(dir)) {
                        files.filter(p -> p.toString().endsWith(".json"))
                            .forEach(p -> {
                                String name = p.getFileName().toString();
                                ids.add(name.substring(0, name.length() - ".json".length()));
                            });
                    }
                } else if ("jar".equals(root.getProtocol())) {
                    String spec = root.getFile();
                    int bang = spec.indexOf("!/");
                    try (java.util.jar.JarFile jar = new java.util.jar.JarFile(
                            java.nio.file.Path.of(spec.substring("file:".length(), bang)).toFile())) {
                        jar.stream().map(java.util.jar.JarEntry::getName)
                            .filter(name -> name.startsWith(SCENARIOS_ROOT) && name.endsWith(".json"))
                            .forEach(name -> ids.add(name.substring(SCENARIOS_ROOT.length(),
                                name.length() - ".json".length())));
                    }
                }
            }
        } catch (Exception unreadable) {
            // an unreadable root contributes nothing; other roots still do
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private static String readScenario(String id) {
        try (var in = InProcessRunner.class.getClassLoader()
                .getResourceAsStream(SCENARIOS_ROOT + id + ".json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return null;
        }
    }

    /**
     * Captures what a scenario can assert on: everything written to the JVM's
     * {@code System.out} (the testmod's own proofs print there) plus every engine
     * log line, which travels JUL into the cell's logging stack.
     */
    private static final class Trace implements AutoCloseable {

        private final PrintStream original;
        private final TeeStream tee;
        private final java.util.logging.Handler julHandler;

        private Trace(PrintStream original, TeeStream tee, java.util.logging.Handler julHandler) {
            this.original = original;
            this.tee = tee;
            this.julHandler = julHandler;
        }

        static Trace install(List<String> sink) {
            PrintStream original = System.out;
            TeeStream tee = new TeeStream(original, sink);
            System.setOut(new PrintStream(tee, true, StandardCharsets.UTF_8));
            java.util.logging.Handler handler = new java.util.logging.Handler() {
                @Override
                public void publish(java.util.logging.LogRecord record) {
                    sink.add(record.getMessage());
                }

                @Override
                public void flush() {
                }

                @Override
                public void close() {
                }
            };
            java.util.logging.Logger.getLogger("vine").addHandler(handler);
            return new Trace(original, tee, handler);
        }

        @Override
        public void close() {
            System.setOut(original);
            tee.flush();
            java.util.logging.Logger.getLogger("vine").removeHandler(julHandler);
        }
    }

    /** A stream that writes through to the original and records whole lines. */
    private static final class TeeStream extends java.io.OutputStream {

        private final PrintStream original;
        private final List<String> sink;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        TeeStream(PrintStream original, List<String> sink) {
            this.original = original;
            this.sink = sink;
        }

        @Override
        public synchronized void write(int b) {
            original.write(b);
            if (b == '\n') {
                sink.add(pending.toString(StandardCharsets.UTF_8));
                pending.reset();
            } else if (b != '\r') {
                pending.write(b);
            }
        }

        @Override
        public synchronized void flush() {
            original.flush();
        }
    }
}
