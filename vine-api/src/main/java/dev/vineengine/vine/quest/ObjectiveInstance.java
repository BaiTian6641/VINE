package dev.vineengine.vine.quest;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonElement;
import dev.vineengine.vine.registry.VineId;

/**
 * One objective inside a quest (sub-15 §2): a type, its parameters, and how many times it
 * must happen. The parameters are a {@link VoxelData} tree rather than a per-type record,
 * because objective types are an extension point — a consumer registers a new type and
 * its codec, and the engine stores whatever that codec reads without learning its shape.
 *
 * @param id     the objective's id, unique inside its quest
 * @param type   the registered objective type
 * @param params the type's own parameters, as authored JSON
 * @param count  how many times the objective must happen (at least one)
 */
public record ObjectiveInstance(VineId id, VineId type, JsonElement params, int count) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ObjectiveInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(ObjectiveInstance::id),
        VineId.CODEC.fieldOf("type").forGetter(ObjectiveInstance::type),
        QuestJson.VALUE.optionalFieldOf("params", QuestJson.empty())
            .forGetter(ObjectiveInstance::params),
        Codec.INT.optionalFieldOf("count", 1).forGetter(ObjectiveInstance::count)
    ).apply(instance, ObjectiveInstance::new));

    public ObjectiveInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(params, "params");
        if (count < 1) {
            throw new IllegalArgumentException("ObjectiveInstance " + id + ": count must be at least 1, got " + count);
        }
    }

    private static JsonElement emptyParams() {
        // An entry with no parameters declared gets an empty object: the shape a consumer's
        // own codec reads when it has nothing to say.
        return QuestJson.empty();
    }
}
