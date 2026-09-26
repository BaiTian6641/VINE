package dev.vineengine.vine.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;

/**
 * Quest authoring JSON as a codec value. The plan's own quest examples write parameters as
 * JSON objects ({@code "params": { "entity": "mymod:boar" }}), so a descriptor carries the
 * JSON and each registered objective or reward type reads it through its own codec — the
 * engine never learns a type's parameter shape, which is what makes objective and reward
 * kinds an extension point rather than a fixed set.
 */
final class QuestJson {

    /** A JSON value, read from and written to the same authoring form. */
    static final Codec<JsonElement> VALUE = Codec.PASSTHROUGH.xmap(
        dynamic -> dynamic.convert(JsonOps.INSTANCE).getValue(),
        json -> new Dynamic<>(JsonOps.INSTANCE, json));

    private QuestJson() {
    }

    /** An empty object: what a descriptor that declares nothing gets. */
    static JsonElement empty() {
        return new JsonObject();
    }
}
