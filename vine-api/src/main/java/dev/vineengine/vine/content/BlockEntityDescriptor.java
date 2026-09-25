package dev.vineengine.vine.content;

import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * A block's engine storage declaration (sub-07 Stage C): which registered
 * {@code VoxelSchema} its holder's {@code VoxelData} root must satisfy, whether that
 * root ticks, and how often.
 *
 * <p>The schema is named by id — the same currency the rest of the data subsystem
 * speaks ({@code VineData.registerSchema} / {@code VineData.of}) — so a descriptor
 * stays plain authoring data, a datapack or JSON descriptor can name it, and the
 * load path validates through the engine's schema registry rather than through a
 * codec the descriptor happened to carry. Attaching this block's holder opens (or
 * creates) a tree of that schema at the block-entity attach point, exactly like every
 * other holder.
 *
 * <p>Ticking is opt-in and explicit: {@code ticking=false} means the cell registers
 * no ticker for this block at all, and {@code tickInterval} is consulted only when
 * {@code ticking} is true. The engine never guesses an interval and never ticks a
 * holder that declared none.
 *
 * <p><b>Invariants:</b> the schema id must resolve to a registered schema by the time
 * a cell materializes the block (a descriptor naming an unregistered schema is
 * rejected at materialization, naming both ids); {@code tickInterval} is at least 1
 * when ticking.
 *
 * @param schemaId     the registered {@code VoxelSchema} a holder's tree must satisfy
 * @param ticking      whether the holder ticks
 * @param tickInterval how many server ticks between ticks (≥ 1); ignored when
 *                     {@code ticking} is false
 */
public record BlockEntityDescriptor(VineId schemaId, boolean ticking, int tickInterval) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<BlockEntityDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("schema").forGetter(BlockEntityDescriptor::schemaId),
        Codec.BOOL.optionalFieldOf("ticking", false).forGetter(BlockEntityDescriptor::ticking),
        Codec.INT.optionalFieldOf("tickInterval", 1).forGetter(BlockEntityDescriptor::tickInterval)
    ).apply(instance, BlockEntityDescriptor::new));

    /** A non-ticking holder over {@code schemaId} — storage without a ticker. */
    public static BlockEntityDescriptor storage(VineId schemaId) {
        return new BlockEntityDescriptor(schemaId, false, 1);
    }

    /** A holder over {@code schemaId} that ticks every {@code interval} server ticks. */
    public static BlockEntityDescriptor ticking(VineId schemaId, int interval) {
        return new BlockEntityDescriptor(schemaId, true, interval);
    }

    public BlockEntityDescriptor {
        Objects.requireNonNull(schemaId, "schemaId");
        if (ticking && tickInterval < 1) {
            throw new IllegalArgumentException("BlockEntityDescriptor: tickInterval must be >= 1 when ticking, got "
                + tickInterval + " (a ticking holder with a zero interval would tick on every call, which no cell"
                + " intends)");
        }
    }
}
