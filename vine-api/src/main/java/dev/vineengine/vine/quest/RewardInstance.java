package dev.vineengine.vine.quest;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonElement;
import dev.vineengine.vine.registry.VineId;

/**
 * One reward of a quest (sub-15 §2): a registered reward type and its parameters. Claim
 * order follows the descriptor's order, and each reward is granted at most once — a
 * player who claims twice gets it once, because the claim is recorded before it is paid.
 *
 * @param type   the registered reward type
 * @param params the type's own parameters, as authored JSON
 */
public record RewardInstance(VineId type, JsonElement params) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<RewardInstance> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("type").forGetter(RewardInstance::type),
        QuestJson.VALUE.optionalFieldOf("params", QuestJson.empty())
            .forGetter(RewardInstance::params)
    ).apply(instance, RewardInstance::new));

    public RewardInstance {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(params, "params");
    }

    private static JsonElement emptyParams() {
        // An entry with no parameters declared gets an empty object: the shape a consumer's
        // own codec reads when it has nothing to say.
        return QuestJson.empty();
    }
}
