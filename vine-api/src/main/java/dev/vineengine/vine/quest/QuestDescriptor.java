package dev.vineengine.vine.quest;

import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.google.gson.JsonElement;
import dev.vineengine.vine.registry.VineId;

/**
 * One quest as data (sub-15 §2): what must happen, in what order it becomes available, and
 * what it pays. Objective ids are unique inside the quest because progress is keyed by
 * them — two objectives sharing an id would share a counter, which is a design nobody asks
 * for and a bug everybody would have to debug.
 *
 * @param id           the quest's id
 * @param chapter      the chapter that lists it, or {@code null} for an unlisted quest
 * @param display      how it presents itself
 * @param dependencies quests that must be claimed before this one unlocks
 * @param objectives   what must happen, in declaration order
 * @param rewards      what it pays, in declaration order
 * @param repeat       whether it can be done again
 */
public record QuestDescriptor(VineId id, VineId chapter, JsonElement display, List<VineId> dependencies,
        List<ObjectiveInstance> objectives, List<RewardInstance> rewards, RepeatPolicy repeat) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<QuestDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(QuestDescriptor::id),
        VineId.CODEC.optionalFieldOf("chapter").forGetter(quest -> java.util.Optional.ofNullable(quest.chapter())),
        QuestJson.VALUE.optionalFieldOf("display", QuestJson.empty()).forGetter(QuestDescriptor::display),
        VineId.CODEC.listOf().optionalFieldOf("dependencies", List.of()).forGetter(QuestDescriptor::dependencies),
        ObjectiveInstance.CODEC.listOf().optionalFieldOf("objectives", List.of()).forGetter(QuestDescriptor::objectives),
        RewardInstance.CODEC.listOf().optionalFieldOf("rewards", List.of()).forGetter(QuestDescriptor::rewards),
        RepeatPolicy.CODEC.optionalFieldOf("repeat", RepeatPolicy.ONCE).forGetter(QuestDescriptor::repeat)
    ).apply(instance, (id, chapter, display, dependencies, objectives, rewards, repeat) -> new QuestDescriptor(id,
        chapter.orElse(null), display, dependencies, objectives, rewards, repeat)));

    public QuestDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(display, "display");
        dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies"));
        objectives = List.copyOf(Objects.requireNonNull(objectives, "objectives"));
        rewards = List.copyOf(Objects.requireNonNull(rewards, "rewards"));
        Objects.requireNonNull(repeat, "repeat");
        java.util.HashSet<VineId> ids = new java.util.HashSet<>(objectives.size());
        for (ObjectiveInstance objective : objectives) {
            if (!ids.add(objective.id())) {
                throw new IllegalArgumentException("QuestDescriptor " + id + ": objective '" + objective.id()
                    + "' is declared twice — progress is keyed by objective id");
            }
        }
        if (dependencies.contains(id)) {
            throw new IllegalArgumentException("QuestDescriptor " + id + " depends on itself");
        }
    }

    private static JsonElement emptyDisplay() {
        return QuestJson.empty();
    }


    /** Whether every objective is satisfied by {@code progress}. */
    public boolean satisfiedBy(QuestProgress progress) {
        for (ObjectiveInstance objective : objectives) {
            if (progress.count(objective.id()) < objective.count()) {
                return false;
            }
        }
        return true;
    }
}
