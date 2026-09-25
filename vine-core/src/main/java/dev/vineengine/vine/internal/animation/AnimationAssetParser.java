package dev.vineengine.vine.internal.animation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import dev.vineengine.vine.animation.AnimationAsset;
import dev.vineengine.vine.world.Vec3;

/**
 * Reads the Blockbench/GeckoLib authoring JSON into the engine's canonical
 * {@link AnimationAsset} (sub-09 §2, Stage A).
 *
 * <p>This is the <em>only</em> place the authoring format is read: geometry
 * ({@code minecraft:geometry[0].bones} — {@code name}/{@code parent}/{@code pivot}/
 * {@code rotation}), the {@code animations} map ({@code loop},
 * {@code animation_length}, per-bone {@code position}/{@code rotation}/{@code scale}
 * channels keyed by string times) and the clips' own marker maps
 * ({@code sound_effects}/{@code particle_effects}, time → {@code {"effect": …}}).
 * Everything downstream speaks the canonical model, so a format change is one edit
 * here rather than one per consumer. Parsing uses Gson, which the engine already
 * carries — no third-party runtime dependency enters {@code vine-api}/{@code vine-core}.
 *
 * <p><b>Authoring bugs fail; they never become defaults.</b> A bone whose parent the
 * geometry does not declare, a self- or cyclic parent chain, a clip animating a bone
 * that does not exist, a keyframe time that is not a finite number, a duplicate
 * keyframe time, and a channel value that is not three numbers each throw
 * {@link IllegalArgumentException} naming the asset, the clip and the bone. What the
 * format genuinely leaves out <em>is</em> defaulted, and only that: absent
 * {@code pivot}/{@code rotation} are the origin and no rotation, an absent channel is
 * the bone's rest value, and an unknown easing name is {@link
 * AnimationAsset.Easing#LINEAR} (per {@link AnimationAsset}'s contract).
 *
 * <p>Output order is pinned: bones and clips are keyed by name in sorted order, and a
 * clip's keyframes and markers are sorted by (time, value) — a hash map's iteration
 * order never reaches the evaluator, so the same bytes parse to the same fixture
 * lines on every cell and every JVM.
 *
 * <p>Render-only data in the same file ({@code cubes}, {@code uv},
 * {@code visible_bounds_*}, {@code texture_width}) is ignored by design: this parser
 * answers "where is the skeleton", not "what does it look like".
 */
public final class AnimationAssetParser {

    private AnimationAssetParser() {
    }

    /** The geometry array's key in the authoring format. */
    private static final String GEOMETRY = "minecraft:geometry";
    /** The clip map's key in the authoring format. */
    private static final String ANIMATIONS = "animations";

    /** Marker sources, in the fixed order markers are read and sorted. */
    private static final List<String> MARKER_SECTIONS = List.of("sound_effects", "particle_effects");

    /**
     * Parses one asset document.
     *
     * @param assetJson the geometry + animations JSON, as exported by the authoring tool
     * @return the canonical asset; bones and clips in sorted key order
     * @throws IllegalArgumentException when the JSON is malformed, or when it declares
     *         something no evaluator could act on (see the class notes for the list)
     */
    public static AnimationAsset parse(String assetJson) {
        if (assetJson == null || assetJson.isBlank()) {
            throw new IllegalArgumentException("animation asset JSON is empty — nothing to parse");
        }
        JsonObject root;
        try {
            JsonElement parsed = JsonParser.parseString(assetJson);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("animation asset JSON must be an object, got "
                    + kind(parsed));
            }
            root = parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException("animation asset JSON is malformed: " + e.getMessage(), e);
        }
        JsonObject geometry = firstGeometry(root);
        String name = assetName(geometry);
        Map<String, AnimationAsset.Bone> bones = bones(geometry, name);
        Map<String, AnimationAsset.Clip> clips = clips(root, name, bones);
        return new AnimationAsset(name, bones, clips);
    }

    // ------------------------------------------------------------------
    // geometry
    // ------------------------------------------------------------------

    private static JsonObject firstGeometry(JsonObject root) {
        JsonElement geometry = root.get(GEOMETRY);
        if (geometry == null || !geometry.isJsonArray() || geometry.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("animation asset has no non-empty '" + GEOMETRY
                + "' array — the geometry is what names the skeleton");
        }
        JsonElement first = geometry.getAsJsonArray().get(0);
        if (!first.isJsonObject()) {
            throw new IllegalArgumentException(GEOMETRY + "[0] must be an object, got " + kind(first));
        }
        return first.getAsJsonObject();
    }

    private static String assetName(JsonObject geometry) {
        JsonElement description = geometry.get("description");
        if (description == null || !description.isJsonObject()) {
            throw new IllegalArgumentException(GEOMETRY + "[0] has no 'description' object");
        }
        String identifier = text(description.getAsJsonObject(), "identifier", GEOMETRY + "[0].description");
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(GEOMETRY
                + "[0].description.identifier must name the asset — it is the name every parse"
                + " error cites");
        }
        return identifier;
    }

    private static Map<String, AnimationAsset.Bone> bones(JsonObject geometry, String asset) {
        JsonElement element = geometry.get("bones");
        if (element == null || !element.isJsonArray() || element.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("asset '" + asset + "': " + GEOMETRY
                + "[0] has no non-empty 'bones' array");
        }
        Map<String, AnimationAsset.Bone> bones = new TreeMap<>();
        JsonArray array = element.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            String where = "asset '" + asset + "': " + GEOMETRY + "[0].bones[" + i + "]";
            JsonElement entry = array.get(i);
            if (!entry.isJsonObject()) {
                throw new IllegalArgumentException(where + " must be an object, got " + kind(entry));
            }
            JsonObject bone = entry.getAsJsonObject();
            String name = text(bone, "name", where);
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException(where + " has no 'name' — every bone is addressable"
                    + " by name, so an unnamed one is an authoring bug");
            }
            String parent = text(bone, "parent", "asset '" + asset + "': bone '" + name + "'");
            if (parent != null && (parent.isBlank() || parent.equals(name) || parent.equals("null"))) {
                throw new IllegalArgumentException("asset '" + asset + "': bone '" + name
                    + "' names parent '" + parent + "', which is not a parent");
            }
            Vec3 pivot = vec3(bone.get("pivot"), "asset '" + asset + "': bone '" + name + "' pivot", Vec3.ZERO);
            Vec3 rotation = vec3(bone.get("rotation"),
                "asset '" + asset + "': bone '" + name + "' rotation", Vec3.ZERO);
            if (bones.put(name, new AnimationAsset.Bone(name, parent, pivot, rotation)) != null) {
                throw new IllegalArgumentException("asset '" + asset + "': bone '" + name
                    + "' is declared twice — a duplicate name makes the skeleton ambiguous");
            }
        }
        validateParents(asset, bones);
        return bones;
    }

    /**
     * Every declared parent must exist, and no chain may loop: the evaluator composes
     * parents, so a dangling or cyclic parent is not a value it could compute.
     */
    private static void validateParents(String asset, Map<String, AnimationAsset.Bone> bones) {
        for (AnimationAsset.Bone bone : bones.values()) {
            if (!bone.isRoot() && !bones.containsKey(bone.parent())) {
                throw new IllegalArgumentException("asset '" + asset + "': bone '" + bone.name()
                    + "' names parent '" + bone.parent() + "', which the geometry does not declare"
                    + " (bones: " + List.copyOf(bones.keySet()) + ")");
            }
        }
        for (AnimationAsset.Bone bone : bones.values()) {
            List<String> chain = new ArrayList<>();
            String current = bone.name();
            while (current != null) {
                if (chain.contains(current)) {
                    chain.add(current);
                    throw new IllegalArgumentException("asset '" + asset + "': bone '" + bone.name()
                        + "' has a cyclic parent chain " + String.join(" -> ", chain));
                }
                chain.add(current);
                AnimationAsset.Bone declared = bones.get(current);
                current = declared == null || declared.isRoot() ? null : declared.parent();
            }
        }
    }

    // ------------------------------------------------------------------
    // clips
    // ------------------------------------------------------------------

    private static Map<String, AnimationAsset.Clip> clips(JsonObject root, String asset,
            Map<String, AnimationAsset.Bone> bones) {
        JsonElement element = root.get(ANIMATIONS);
        if (element == null || !element.isJsonObject() || element.getAsJsonObject().isEmpty()) {
            throw new IllegalArgumentException("asset '" + asset + "' has no non-empty '" + ANIMATIONS
                + "' map — an animation asset with no clips is an authoring bug, not an empty asset");
        }
        Map<String, AnimationAsset.Clip> clips = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String clipName = entry.getKey();
            String where = "asset '" + asset + "': clip '" + clipName + "'";
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException(where + " must be an object, got " + kind(entry.getValue()));
            }
            JsonObject clip = entry.getValue().getAsJsonObject();
            Double length = number(clip.get("animation_length"), where + " 'animation_length'");
            if (length == null || !(length > 0.0D)) {
                throw new IllegalArgumentException(where
                    + " must declare a positive 'animation_length' in seconds, got "
                    + (length == null ? "nothing" : Double.toString(length)));
            }
            boolean loop = loop(clip.get("loop"), where);
            Map<String, AnimationAsset.Channel> channels = channels(clip.get("bones"), where, bones);
            List<AnimationAsset.Marker> markers = markers(clip, where);
            clips.put(clipName, new AnimationAsset.Clip(clipName, length, loop, channels, markers));
        }
        return clips;
    }

    /**
     * The format's loop flag: a boolean, or the strings the exporters write
     * ({@code "loop"}, {@code "true"}, {@code "hold_on_last_frame"}). An unrecognised
     * token is an authoring bug — guessing here would silently change whether a clip
     * repeats.
     */
    private static boolean loop(JsonElement element, String where) {
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isString()) {
                String token = primitive.getAsString().trim().toLowerCase(Locale.ROOT);
                return switch (token) {
                    case "loop", "true" -> true;
                    case "false", "hold_on_last_frame", "hold", "once", "" -> false;
                    default -> throw new IllegalArgumentException(where + " has an unrecognised 'loop' value '"
                        + primitive.getAsString() + "' — expected loop/true/hold_on_last_frame/false");
                };
            }
        }
        throw new IllegalArgumentException(where + " 'loop' must be a boolean or a string, got " + kind(element));
    }

    private static Map<String, AnimationAsset.Channel> channels(JsonElement element, String where,
            Map<String, AnimationAsset.Bone> bones) {
        if (element == null || element.isJsonNull()) {
            return Map.of();
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(where + " 'bones' must be an object of bone → channels, got "
                + kind(element));
        }
        Map<String, AnimationAsset.Channel> channels = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String bone = entry.getKey();
            String at = where + ", bone '" + bone + "'";
            if (!bones.containsKey(bone)) {
                throw new IllegalArgumentException(at + " is animated but the geometry does not declare it"
                    + " (bones: " + List.copyOf(bones.keySet()) + ")");
            }
            if (!entry.getValue().isJsonObject()) {
                throw new IllegalArgumentException(at + ": channels must be an object, got "
                    + kind(entry.getValue()));
            }
            JsonObject declared = entry.getValue().getAsJsonObject();
            channels.put(bone, new AnimationAsset.Channel(
                keyframes(declared.get("position"), at + " position"),
                keyframes(declared.get("rotation"), at + " rotation"),
                keyframes(declared.get("scale"), at + " scale")));
        }
        return channels;
    }

    /** Parses one channel's keyframes, in time order, rejecting duplicate times. */
    private static List<AnimationAsset.Keyframe> keyframes(JsonElement element, String where) {
        if (element == null || element.isJsonNull()) {
            return List.of();
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(where + " must be an object of time → value, got "
                + kind(element));
        }
        Map<Double, AnimationAsset.Keyframe> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            double time = time(key, where);
            Value value = keyframeValue(entry.getValue(), where + " at " + key);
            if (sorted.put(time, new AnimationAsset.Keyframe(time, value.vector, value.easing)) != null) {
                throw new IllegalArgumentException(where + ": two keyframes sit at "
                    + Double.toString(time) + "s (one written '" + key + "') — a duplicated time is an"
                    + " authoring bug");
            }
        }
        return List.copyOf(sorted.values());
    }

    /** The value and easing of one keyframe: {@code [x,y,z]}, or {@code {post|pre, lerp_mode}}. */
    private record Value(Vec3 vector, AnimationAsset.Easing easing) {
    }

    private static Value keyframeValue(JsonElement element, String where) {
        if (element.isJsonArray()) {
            return new Value(vector(element, where), AnimationAsset.Easing.LINEAR);
        }
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException(where + " must be [x,y,z] or an object with 'post'/'pre', got "
                + kind(element));
        }
        JsonObject declared = element.getAsJsonObject();
        JsonElement vector = declared.has("post") ? declared.get("post") : declared.get("pre");
        if (vector == null) {
            throw new IllegalArgumentException(where
                + " has neither 'post' nor 'pre' — one of them is the value at that time");
        }
        AnimationAsset.Easing easing = AnimationAsset.Easing.LINEAR;
        for (String token : List.of("lerp_mode", "easing")) {
            String name = text(declared, token, where);
            if (name != null && !name.isBlank()) {
                easing = easingNamed(name);
                break;
            }
        }
        return new Value(vector(vector, where), easing);
    }

    /**
     * Maps the format's easing names onto {@link AnimationAsset.Easing}: the exporters
     * write both {@code lerp_mode} ("linear"/"catmullrom") and the fuller
     * {@code easeInOutQuad}-style names, so both spellings are accepted. Anything
     * unrecognised is {@code LINEAR}, which is {@link AnimationAsset}'s own contract
     * for an unknown name.
     */
    private static AnimationAsset.Easing easingNamed(String name) {
        String token = name.trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        if (token.startsWith("catmull")) {
            return AnimationAsset.Easing.CATMULLROM;
        }
        if (token.startsWith("step") || token.startsWith("hold")) {
            return AnimationAsset.Easing.STEP;
        }
        if (token.contains("inout") || token.contains("ineout")) {
            return AnimationAsset.Easing.EASE_IN_OUT;
        }
        if (token.startsWith("easein")) {
            return AnimationAsset.Easing.EASE_IN;
        }
        if (token.startsWith("easeout")) {
            return AnimationAsset.Easing.EASE_OUT;
        }
        if (token.startsWith("ease") || token.startsWith("out")) {
            // "ease", "easeQuad", "outQuad", … — the format's family default.
            return AnimationAsset.Easing.EASE_OUT;
        }
        return AnimationAsset.Easing.LINEAR;
    }

    /**
     * The clip's markers, from its own {@code sound_effects} and
     * {@code particle_effects} maps. VINE namespaces the markers that drive combat
     * timing ({@code vine:active_start} …), so an author's own sound and particle
     * markers sit in the same maps and travel with them; every marker is kept, and the
     * evaluator filters by name.
     */
    private static List<AnimationAsset.Marker> markers(JsonObject clip, String where) {
        List<AnimationAsset.Marker> markers = new ArrayList<>();
        for (String section : MARKER_SECTIONS) {
            JsonElement element = clip.get(section);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException(where + " '" + section + "' must be an object of time →"
                    + " effect, got " + kind(element));
            }
            for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                double time = time(entry.getKey(), where + " '" + section + "'");
                for (JsonElement effect : effects(entry.getValue(), where + " '" + section + "' at "
                        + entry.getKey())) {
                    if (!effect.isJsonObject()) {
                        throw new IllegalArgumentException(where + " '" + section + "' at " + entry.getKey()
                            + ": each effect must be an object with 'effect', got " + kind(effect));
                    }
                    String name = text(effect.getAsJsonObject(), "effect", where + " '" + section + "' at "
                        + entry.getKey());
                    if (name == null || name.isBlank()) {
                        throw new IllegalArgumentException(where + " '" + section + "' at " + entry.getKey()
                            + ": an effect has no 'effect' name — an unnamed marker drives nothing");
                    }
                    markers.add(new AnimationAsset.Marker(time, name));
                }
            }
        }
        markers.sort(Comparator.comparingDouble(AnimationAsset.Marker::timeSeconds)
            .thenComparing(AnimationAsset.Marker::name));
        List<AnimationAsset.Marker> distinct = new ArrayList<>(markers.size());
        for (AnimationAsset.Marker marker : markers) {
            if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(marker)) {
                distinct.add(marker);
            }
        }
        return List.copyOf(distinct);
    }

    /** An effect entry is one object or an array of them (the format allows both). */
    private static List<JsonElement> effects(JsonElement element, String where) {
        if (element.isJsonArray()) {
            List<JsonElement> effects = new ArrayList<>();
            element.getAsJsonArray().forEach(effects::add);
            return effects;
        }
        if (element.isJsonObject()) {
            return List.of(element);
        }
        throw new IllegalArgumentException(where + " must be an effect object or an array of them, got "
            + kind(element));
    }

    // ------------------------------------------------------------------
    // primitives
    // ------------------------------------------------------------------

    /** A keyframe or marker time: the key must be a finite number. */
    private static double time(String key, String where) {
        String trimmed = key == null ? "" : key.trim();
        double parsed;
        try {
            parsed = Double.parseDouble(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(where + ": '" + key + "' is not a keyframe time — times are"
                + " numbers in seconds", e);
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(where + ": '" + key + "' is not a finite time");
        }
        return parsed;
    }

    /**
     * Three numbers, or the given fallback when the field is absent. A present but
     * malformed value is an authoring bug: every component must be numeric, and the
     * error names the component it rejected.
     */
    private static Vec3 vec3(JsonElement element, String where, Vec3 fallback) {
        if (element == null || element.isJsonNull()) {
            return Objects.requireNonNull(fallback, "fallback");
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(where + " must be [x,y,z], got " + kind(element));
        }
        return vector(element, where);
    }

    private static Vec3 vector(JsonElement element, String where) {
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException(where + " must be [x,y,z], got " + kind(element));
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException(where + " must have exactly three numbers, got "
                + array.size() + ": " + array);
        }
        double[] components = new double[3];
        String[] axis = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            JsonElement component = array.get(i);
            if (!component.isJsonPrimitive()) {
                throw new IllegalArgumentException(where + ": " + axis[i] + " must be a number, got "
                    + kind(component));
            }
            try {
                components[i] = component.getAsJsonPrimitive().getAsDouble();
            } catch (NumberFormatException | UnsupportedOperationException e) {
                throw new IllegalArgumentException(where + ": " + axis[i] + " must be a number, got '"
                    + component.getAsString() + "'", e);
            }
            if (!Double.isFinite(components[i])) {
                throw new IllegalArgumentException(where + ": " + axis[i] + " must be finite, got '"
                    + component.getAsString() + "'");
            }
        }
        return Vec3.of(components[0], components[1], components[2]);
    }

    private static Double number(JsonElement element, String where) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(where + " must be a number, got " + kind(element));
        }
        double value = element.getAsDouble();
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(where + " must be finite, got " + element);
        }
        return value;
    }

    private static String text(JsonObject object, String field, String where) {
        JsonElement element = object.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(where + ": '" + field + "' must be a string, got "
                + kind(element));
        }
        return element.getAsString();
    }

    private static String kind(JsonElement element) {
        if (element == null) {
            return "nothing";
        }
        return switch (element) {
            case JsonObject ignored -> "an object";
            case JsonArray ignored -> "an array";
            case JsonPrimitive primitive -> primitive.isBoolean() ? "a boolean"
                : primitive.isNumber() ? "a number" : "a string";
            default -> "a null";
        };
    }
}
