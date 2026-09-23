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

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
