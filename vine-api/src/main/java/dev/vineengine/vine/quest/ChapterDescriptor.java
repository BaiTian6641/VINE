package dev.vineengine.vine.quest;

import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonElement;
import dev.vineengine.vine.registry.VineId;

/**
 * A chapter: the grouping a quest board shows (sub-15 §2). Quests are declared inside
 * chapters in display order, and a chapter holds no logic of its own — it exists so a
 * consumer can lay out a hunt list without inventing an ordering mechanism.
 *
 * @param id     the chapter's id
 * @param display how it presents itself (title, icon, description: engine data, not a record)
 * @param quests the quests it lists, in display order
 * @param order  where the chapter sits among its siblings
 */
public record ChapterDescriptor(VineId id, JsonElement display, List<VineId> quests, int order) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ChapterDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(ChapterDescriptor::id),
        QuestJson.VALUE.optionalFieldOf("display", QuestJson.empty()).forGetter(ChapterDescriptor::display),
        VineId.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(ChapterDescriptor::quests),
        Codec.INT.optionalFieldOf("order", 0).forGetter(ChapterDescriptor::order)
    ).apply(instance, ChapterDescriptor::new));

    public ChapterDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(display, "display");
        quests = List.copyOf(Objects.requireNonNull(quests, "quests"));
    }

    private static JsonElement emptyDisplay() {
        return QuestJson.empty();
    }
}
