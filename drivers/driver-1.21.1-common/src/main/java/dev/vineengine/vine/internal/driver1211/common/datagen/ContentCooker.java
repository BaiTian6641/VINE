package dev.vineengine.vine.internal.driver1211.common.datagen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Driver-owned content cooking (sub-02 Stage E, plan §5.2): turns a consumer's
 * <em>source assets</em> into the derived resource files a Minecraft cell needs —
 * blockstate JSON, block/item models and {@code sounds.json} — so consumers never
 * run per-version datagen.
 *
 * <p>Deterministic by construction: sources are visited in sorted order, output
 * JSON is emitted with fixed formatting (2-space indent, LF, trailing newline)
 * and {@code sounds.json} objects are re-serialized with sorted keys. The same
 * inputs cook the same bytes on every cell and every run.
 *
 * <p>Source layout (inside a consumer's {@code assets/}):
 * <pre>
 *   &lt;ns&gt;/textures/block/&lt;name&gt;.png  -&gt; blockstates/&lt;name&gt;.json + models/block/&lt;name&gt;.json
 *   &lt;ns&gt;/textures/item/&lt;name&gt;.png   -&gt; models/item/&lt;name&gt;.json
 *   &lt;ns&gt;/sounds.source.json          -&gt; sounds.json (canonicalized)
 * </pre>
 */
public final class ContentCooker {

    private static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        // Blockstate variant keys are full of '='; Gson's HTML escaping would spell
        // each one \u003d. Vanilla parses either form, but a cooked file a human
        // reads should read like the state it names.
        .disableHtmlEscaping()
        .create();

    private ContentCooker() {
    }

    public static void main(String[] args) throws IOException {
        Path assets = null;
        Path out = null;
        String cell = "<cell>";
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--assets" -> assets = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--cell" -> cell = args[++i];
                default -> {
                    System.err.println("unknown argument: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (assets == null || out == null) {
            System.err.println("usage: ContentCooker --cell <id> --assets <dir> --out <dir>");
            System.exit(2);
        }
        if (!Files.isDirectory(assets)) {
            System.err.println("[datagen] no source assets at " + assets + " — nothing to cook");
            return;
        }
        List<String> cooked = new ArrayList<>();
        for (String namespace : sortedChildren(assets)) {
            Path nsDir = assets.resolve(namespace);
            if (!Files.isDirectory(nsDir)) {
                continue;
            }
            cookTextures(nsDir, namespace, out, "block", cooked);
            cookTextures(nsDir, namespace, out, "item", cooked);
            cookBlockstates(nsDir, namespace, out, cooked);
            cookSounds(nsDir, namespace, out, cooked);
        }
        System.out.println("[datagen] " + cell + ": cooked " + cooked.size() + " file(s)");
    }

    private static void cookTextures(Path nsDir, String namespace, Path out, String kind, List<String> cooked)
            throws IOException {
        Path textures = nsDir.resolve("textures").resolve(kind);
        if (!Files.isDirectory(textures)) {
            return;
        }
        for (String file : sortedChildren(textures)) {
            if (!file.endsWith(".png")) {
                continue;
            }
            String name = file.substring(0, file.length() - 4);
            if ("block".equals(kind)) {
                write(out, namespace + "/blockstates/" + name + ".json", """
                    {
                      "variants": {
                        "": {
                          "model": "%s:block/%s"
                        }
                      }
                    }
                    """.formatted(namespace, name), cooked);
            }
            String parent = "block".equals(kind) ? "minecraft:block/cube_all" : "minecraft:item/generated";
            String textureKey = "block".equals(kind) ? "all" : "layer0";
            write(out, namespace + "/models/" + kind + "/" + name + ".json", """
                {
                  "parent": "%s",
                  "textures": {
                    "%s": "%s:%s/%s"
                  }
                }
                """.formatted(parent, textureKey, namespace, kind, name), cooked);
        }
    }

    /**
     * Cooks blockstates for blocks whose state model is <em>flattened</em> (sub-07
     * Stage B): a block with N declared properties has a state per combination of
     * their values, and the client can only render such a block if its blockstate
     * JSON names every combination.
     *
     * <p>The declaration lives in {@code assets/&lt;ns&gt;/blockstates.source.json} —
     * source data, not derived data, because a texture's presence cannot say how
     * many states a block has:
     * <pre>
     * {
     *   "stateblock": {
     *     "properties": { "lit": ["true", "false"], "level": ["0", "1", "2", "3"] },
     *     "model": "vine_test:block/stateblock"
     *   }
     * }
     * </pre>
     * The enumeration is the cartesian product of the declared values in declared
     * order, last property varying fastest — the same total order the engine's own
     * state table uses, so the cooked file and the engine agree on what state zero
     * is. A block named here overrides its texture-derived single-variant
     * blockstate; a block with no entry keeps the placeholder shape.
     */
    private static void cookBlockstates(Path nsDir, String namespace, Path out, List<String> cooked) throws IOException {
        Path source = nsDir.resolve("blockstates.source.json");
        if (!Files.isRegularFile(source)) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(source, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("[datagen] " + source + " is not valid JSON: " + e.getMessage(), e);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("[datagen] " + source + " must be a JSON object of block name -> declaration");
        }
        // Blocks are visited in sorted order (deterministic), but a block's
        // properties keep the order the source declares them in: that order is the
        // flattening order the engine's state table uses, so the first value of the
        // first property is state zero on both sides.
        for (String block : sorted(parsed.getAsJsonObject().keySet())) {
            JsonObject declaration = parsed.getAsJsonObject().getAsJsonObject(block);
            if (declaration == null || !declaration.has("properties") || !declaration.has("model")) {
                throw new IOException("[datagen] " + source + ": '" + block
                    + "' must declare both 'properties' and 'model'");
            }
            write(out, namespace + "/blockstates/" + block + ".json",
                variants(namespace, block, declaration), cooked);
        }
    }

    /** The blockstate document for one declared block: one variant per state, in declared order. */
    private static String variants(String namespace, String block, JsonObject declaration) {
        JsonObject properties = declaration.getAsJsonObject("properties");
        String model = declaration.get("model").getAsString();
        List<String> names = new ArrayList<>(properties.keySet());
        List<List<String>> axes = new ArrayList<>(names.size());
        for (String name : names) {
            List<String> values = new ArrayList<>();
            for (JsonElement value : properties.getAsJsonArray(name)) {
                values.add(value.getAsString());
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("[datagen] " + namespace + "/" + block + ": property '" + name
                    + "' declares no values (a property with no values has no state table)");
            }
            axes.add(values);
        }
        JsonObject variants = new JsonObject();
        int total = 1;
        for (List<String> axis : axes) {
            total *= axis.size();
        }
        for (int index = 0; index < total; index++) {
            // Row-major over the declared axes (first property most significant),
            // which is the engine state table's own order: state zero is the first
            // value of every property, on the client as well as in the engine.
            int[] digits = new int[axes.size()];
            int remainder = index;
            for (int axis = axes.size() - 1; axis >= 0; axis--) {
                digits[axis] = remainder % axes.get(axis).size();
                remainder /= axes.get(axis).size();
            }
            List<String> parts = new ArrayList<>(axes.size());
            for (int axis = 0; axis < axes.size(); axis++) {
                parts.add(names.get(axis) + "=" + axes.get(axis).get(digits[axis]));
            }
            JsonObject variant = new JsonObject();
            variant.addProperty("model", model);
            variants.add(String.join(",", parts), variant);
        }
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("variants", variants);
        return GSON.toJson(root) + System.lineSeparator();
    }

    /** Sorted copy of a key set — the deterministic visit order every cook uses. */
    private static List<String> sorted(java.util.Set<String> keys) {
        List<String> sorted = new ArrayList<>(keys);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    private static void cookSounds(Path nsDir, String namespace, Path out, List<String> cooked) throws IOException {
        Path source = nsDir.resolve("sounds.source.json");
        if (!Files.isRegularFile(source)) {
            return;
        }
        JsonElement parsed = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8));
        write(out, namespace + "/sounds.json", canonical(parsed) + "\n", cooked);
    }

    /** Re-serializes JSON with sorted object keys so identical inputs give identical bytes. */
    private static String canonical(JsonElement element) {
        if (element.isJsonObject()) {
            Map<String, JsonElement> sorted = new TreeMap<>();
            element.getAsJsonObject().entrySet().forEach(e -> sorted.put(e.getKey(), e.getValue()));
            StringBuilder out = new StringBuilder("{\n");
            int i = 0;
            for (Map.Entry<String, JsonElement> entry : sorted.entrySet()) {
                out.append("  \"").append(entry.getKey()).append("\": ")
                    .append(canonical(entry.getValue()).replace("\n", "\n  "));
                if (++i < sorted.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            return out.append('}').toString();
        }
        if (element.isJsonArray()) {
            StringBuilder out = new StringBuilder("[\n");
            int i = 0;
            var array = element.getAsJsonArray();
            for (JsonElement child : array) {
                out.append("  ").append(canonical(child).replace("\n", "\n  "));
                if (++i < array.size()) {
                    out.append(',');
                }
                out.append('\n');
            }
            return out.append(']').toString();
        }
        return GSON.toJson(element);
    }

    private static void write(Path out, String relative, String content, List<String> cooked) throws IOException {
        Path target = out.resolve("assets").resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        cooked.add(relative);
    }

    private static List<String> sortedChildren(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            List<String> names = new ArrayList<>();
            children.forEach(child -> names.add(child.getFileName().toString()));
            names.sort(String::compareTo);
            return names;
        }
    }
}
