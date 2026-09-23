package dev.vineengine.vine.content;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Minimal per-item tuning carried by an {@link ItemDescriptor} (sub-07 Stage A
 * — stack size only; rarity, fire-resistance and the rest of the §5.3 set land
 * with later stages).
 *
 * <p><b>Invariant:</b> durability is deliberately absent — damage is
 * {@code VoxelData} on the stack (sub-03), never item identity (§5.3/§5.4),
 * so no tuning field may ever encode it.
 *
 * @param stackSize max items per stack; 1 = unstackable, 99 = vanilla ceiling
 */
public record ItemTuning(int stackSize) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ItemTuning> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.intRange(1, 99).fieldOf("stackSize").forGetter(ItemTuning::stackSize)
    ).apply(instance, ItemTuning::new));

    public ItemTuning {
        if (stackSize < 1 || stackSize > 99) {
            throw new IllegalArgumentException(
                "ItemTuning.stackSize must be within 1..99 (1 = unstackable): " + stackSize);
        }
    }
}
