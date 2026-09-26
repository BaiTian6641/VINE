package dev.vineengine.vine.quest;

import com.mojang.serialization.Codec;

import dev.vineengine.vine.registry.DescriptorClass;
import dev.vineengine.vine.registry.DescriptorType;
import dev.vineengine.vine.registry.VineId;

/**
 * The two design-registry kinds quests are authored in (sub-15 §2): chapters and quests.
 *
 * <p><b>Design, not structural.</b> A quest is content a pack author edits and reloads —
 * the opposite of an entity kind, which a running world cannot redefine. Both kinds sync to
 * clients, because a quest board is something a player looks at.
 */
final class QuestTypes {

    private QuestTypes() {
    }

    static DescriptorType<ChapterDescriptor> chapterType() {
        return new DesignType<>(VineId.of("vine", "quest_chapter"), ChapterDescriptor.CODEC);
    }

    static DescriptorType<QuestDescriptor> questType() {
        return new DesignType<>(VineId.of("vine", "quest"), QuestDescriptor.CODEC);
    }

    /** The design-registry shape: datapack-authorable, reloadable, client-visible. */
    private record DesignType<D>(VineId registryId, Codec<D> codec) implements DescriptorType<D> {

        @Override
        public DescriptorClass descriptorClass() {
            return DescriptorClass.DESIGN;
        }

        @Override
        public boolean syncToClient() {
            return true;
        }
    }
}
