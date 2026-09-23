package dev.vineengine.vine.internal.tck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Golden-fixture verifier for the driver-owned content pipeline (sub-02 Stage E):
 * byte-compares each cell's cooked resources
 * ({@code drivers/driver-<cell>/build/datagen/<cell>}) against the committed
 * goldens ({@code vine-tck/fixtures/datagen/<cell>}), reporting missing, extra
 * and differing files. Exit 0 only when every cell matches byte-for-byte — the
 * determinism guarantee the pipeline makes (§5.2: consumers never run per-version
 * datagen, so cooked bytes must be reproducible everywhere).
 */
public final class DatagenVerifier {

    private DatagenVerifier() {
    }

    public static void main(String[] args) throws IOException {
        Path rootDir = null;
        Path fixturesDir = null;
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--root-dir" -> rootDir = Path.of(args[++i]);
                case "--fixtures-dir" -> fixturesDir = Path.of(args[++i]);
                case "--cells" -> cells.add(args[++i]);
                default -> {
                    System.err.println("[TCK] unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (rootDir == null || fixturesDir == null || cells.isEmpty()) {
            System.err.println("usage: DatagenVerifier --root-dir <dir> --fixtures-dir <dir> --cells <cell> [--cells <cell>...]");
            System.exit(2);
        }
        List<String> failures = new ArrayList<>();
        for (String cell : cells) {
            Path generated = rootDir.resolve("drivers").resolve("driver-" + cell)
                .resolve("build").resolve("datagen").resolve(cell);
            Path golden = fixturesDir.resolve(cell);
            if (!Files.isDirectory(golden)) {
                failures.add(cell + ": no golden fixtures at " + golden);
                continue;
            }
            if (!Files.isDirectory(generated)) {
                failures.add(cell + ": no cooked output at " + generated + " (run the cell's datagenContent task)");
                continue;
            }
            List<String> generatedFiles = relativeFiles(generated);
            List<String> goldenFiles = relativeFiles(golden);
            TreeSet<String> all = new TreeSet<>(generatedFiles);
            all.addAll(goldenFiles);
            for (String relative : all) {
                Path g = generated.resolve(relative);
                Path k = golden.resolve(relative);
                if (!Files.isRegularFile(g)) {
                    failures.add(cell + ": missing cooked file " + relative);
                } else if (!Files.isRegularFile(k)) {
                    failures.add(cell + ": cooked file not in goldens " + relative);
                } else if (!java.util.Arrays.equals(Files.readAllBytes(g), Files.readAllBytes(k))) {
                    failures.add(cell + ": bytes differ " + relative);
                }
            }
            System.out.println("[TCK] datagen " + cell + ": " + generatedFiles.size()
                + " cooked file(s) vs " + goldenFiles.size() + " golden file(s)");
        }
        if (!failures.isEmpty()) {
            failures.forEach(failure -> System.out.println("[TCK] datagen FAIL — " + failure));
            System.exit(1);
        }
        System.out.println("[TCK] datagen fixtures: every 1.21.1 cell byte-identical to its goldens");
    }

    private static List<String> relativeFiles(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                .map(path -> root.relativize(path).toString())
                .sorted()
                .toList();
        }
    }
}
