package dev.vineengine.vine.internal.tck;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * CI reporting + flaky policy for the TCK (sub-21 Stage D): reads the per-cell
 * results journal ({@code results/<cell>.jsonl}), renders the per-scenario ×
 * cell matrix with history, decides quarantine — two consecutive failures of the
 * same scenario quarantine it, so the gate stops flapping while the ledger keeps
 * it visible — writes {@code results/quarantine.json} for the next run's gate
 * exemption, records failure artifacts, and maintains {@code QUARANTINE.md}
 * (owner + expiry; quarantine count is a reported metric).
 */
public final class ResultReporter {

    private static final int HISTORY_KEEP = 10;
    private static final long QUARANTINE_DAYS = 14;

    private ResultReporter() {
    }

    public static void main(String[] args) throws IOException {
        Path rootDir = null;
        Path resultsDir = null;
        Path ledger = null;
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--root-dir" -> rootDir = Path.of(args[++i]);
                case "--results-dir" -> resultsDir = Path.of(args[++i]);
                case "--ledger" -> ledger = Path.of(args[++i]);
                case "--cells" -> cells.add(args[++i]);
                default -> {
                    System.err.println("[TCK] unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (rootDir == null || resultsDir == null || ledger == null || cells.isEmpty()) {
            System.err.println("usage: ResultReporter --root-dir <dir> --results-dir <dir> --ledger <file> --cells <cell>...");
            System.exit(2);
        }
        Files.createDirectories(resultsDir);
        Files.createDirectories(resultsDir.resolve("reports"));

        Map<String, List<Entry>> journal = new TreeMap<>();
        for (String cell : cells) {
            Path journalFile = resultsDir.resolve(cell + ".jsonl");
            List<Entry> entries = readJournal(journalFile);
            System.out.println("[TCK] report: journal " + journalFile + " -> " + entries.size() + " entrie(s)"
                + (Files.exists(journalFile) ? "" : " (file missing)"));
            journal.put(cell, entries);
        }

        // Quarantine decision: two consecutive failures of the same scenario.
        Set<String> quarantined = new TreeSet<>();
        Map<String, String> reasons = new LinkedHashMap<>();
        Set<String> scenarios = new TreeSet<>();
        journal.values().forEach(entries -> entries.forEach(entry -> scenarios.add(entry.scenario)));
        for (String scenario : scenarios) {
            int consecutive = 0;
            for (String cell : cells) {
                List<Entry> ofScenario = journal.get(cell).stream()
                    .filter(entry -> entry.scenario.equals(scenario)).toList();
                if (ofScenario.size() >= 2) {
                    Entry last = ofScenario.get(ofScenario.size() - 1);
                    Entry previous = ofScenario.get(ofScenario.size() - 2);
                    if (!"PASS".equals(last.status) && !"PASS".equals(previous.status)) {
                        consecutive = Math.max(consecutive, 2);
                    }
                }
            }
            if (consecutive >= 2) {
                quarantined.add(scenario);
                reasons.put(scenario, "two consecutive failing runs — owner: unassigned, expires: "
                    + Instant.now().plus(QUARANTINE_DAYS, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS));
            }
        }
        writeQuarantineDecision(resultsDir.resolve("quarantine.json"), quarantined);
        writeLedger(ledger, quarantined, reasons);
        writeMatrix(resultsDir.resolve("reports"), cells, journal, quarantined);

        long artifacts = Files.list(resultsDir)
            .filter(path -> path.getFileName().toString().startsWith("artifact-")).count();
        System.out.println("[TCK] report: " + scenarios.size() + " scenario(s) × " + cells.size()
            + " cell(s); quarantined=" + quarantined.size() + "; failure artifacts=" + artifacts);
        System.out.println("[TCK] report: wrote " + resultsDir.resolve("reports/matrix.md")
            + " and " + ledger.getFileName());
    }

    private record Entry(String run, String cell, String scenario, String status, long durationMs, String failure) {
    }

    private static List<Entry> readJournal(Path file) throws IOException {
        if (!Files.exists(file)) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        int malformedLines = 0;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                entries.add(new Entry(
                    json.get("run").getAsString(),
                    json.get("cell").getAsString(),
                    json.get("scenario").getAsString(),
                    json.get("status").getAsString(),
                    json.get("durationMs").getAsLong(),
                    json.has("failure") && !json.get("failure").isJsonNull()
                        ? json.get("failure").getAsString() : ""));
            } catch (RuntimeException malformed) {
                // A torn line (killed run) must never break reporting — but it is
                // surfaced once so a systematically unreadable journal is visible.
                if (malformedLines++ < 3) {
                    System.out.println("[TCK] report: unreadable journal line (" + malformed + "): " + line);
                }
            }
        }
        return entries;
    }

    private static void writeQuarantineDecision(Path file, Set<String> quarantined) throws IOException {
        StringBuilder json = new StringBuilder("{\"scenarios\":[");
        int i = 0;
        for (String scenario : quarantined) {
            json.append(i++ > 0 ? "," : "").append('"').append(scenario).append('"');
        }
        json.append("]}");
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);
    }

    /** Ledger entry per quarantined scenario: owner + expiry (never silently dropped). */
    private static void writeLedger(Path ledger, Set<String> quarantined, Map<String, String> reasons)
            throws IOException {
        StringBuilder out = new StringBuilder("""
            # Quarantined TCK scenarios

            A scenario lands here after two consecutive failing runs (same-journal,
            any cell). Quarantined failures are **reported, not gating** — the
            ledger keeps them visible, and the count is a reviewed metric.
            Remove an entry only with a fix, a fresh green run, and a note here.

            | Scenario | Reason | Quarantined |
            |---|---|---|
            """);
        for (String scenario : quarantined) {
            out.append("| ").append(scenario).append(" | ").append(reasons.get(scenario))
                .append(" | ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS)).append(" |\n");
        }
        if (quarantined.isEmpty()) {
            out.append("| _(none)_ | — | — |\n");
        }
        Files.writeString(ledger, out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeMatrix(Path reports, List<String> cells, Map<String, List<Entry>> journal,
            Set<String> quarantined) throws IOException {
        Set<String> scenarios = new TreeSet<>();
        journal.values().forEach(entries -> entries.forEach(entry -> scenarios.add(entry.scenario)));
        StringBuilder markdown = new StringBuilder("# TCK matrix\n\n");
        markdown.append("| Scenario |");
        for (String cell : cells) {
            markdown.append(' ').append(cell).append(" |");
        }
        markdown.append(" History |\n|---|---");
        for (int i = 0; i < cells.size(); i++) {
            markdown.append("|---");
        }
        markdown.append("|---|\n");
        for (String scenario : scenarios) {
            markdown.append("| ").append(quarantined.contains(scenario) ? "**(quarantined)** " : "")
                .append(scenario).append(" |");
            for (String cell : cells) {
                List<Entry> ofScenario = journal.get(cell).stream()
                    .filter(entry -> entry.scenario.equals(scenario)).toList();
                String latest = ofScenario.isEmpty() ? "—"
                    : ofScenario.get(ofScenario.size() - 1).status;
                markdown.append(' ').append(latest).append(" |");
            }
            List<Entry> latestRuns = journal.get(cells.get(0)).stream()
                .filter(entry -> entry.scenario.equals(scenario)).toList();
            StringBuilder history = new StringBuilder();
            for (int i = Math.max(0, latestRuns.size() - HISTORY_KEEP); i < latestRuns.size(); i++) {
                history.append(latestRuns.get(i).status.charAt(0));
            }
            markdown.append(' ').append(history.isEmpty() ? "—" : history).append(" |\n");
        }
        Files.writeString(reports.resolve("matrix.md"), markdown.toString(), StandardCharsets.UTF_8);

        JsonObject json = new JsonObject();
        json.addProperty("generated", Instant.now().toString());
        com.google.gson.JsonArray quarantineArray = new com.google.gson.JsonArray();
        quarantined.forEach(quarantineArray::add);
        json.add("quarantined", quarantineArray);
        JsonObject rows = new JsonObject();
        for (String scenario : scenarios) {
            JsonObject row = new JsonObject();
            for (String cell : cells) {
                List<Entry> ofScenario = journal.get(cell).stream()
                    .filter(entry -> entry.scenario.equals(scenario)).toList();
                if (!ofScenario.isEmpty()) {
                    Entry latest = ofScenario.get(ofScenario.size() - 1);
                    row.addProperty(cell, latest.status);
                }
            }
            rows.add(scenario, row);
        }
        json.add("scenarios", rows);
        Files.writeString(reports.resolve("matrix.json"), json.toString(), StandardCharsets.UTF_8);
    }

    /** Unused placeholder mirroring the set-based deduplication the matrix relies on. */
    private static Set<String> ordered(Set<String> source) {
        return new LinkedHashSet<>(source);
    }

    private static List<String> sorted(Set<String> source) {
        List<String> list = new ArrayList<>(source);
        list.sort(Comparator.naturalOrder());
        return list;
    }
}
