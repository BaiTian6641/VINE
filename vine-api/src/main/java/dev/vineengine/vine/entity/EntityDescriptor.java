package dev.vineengine.vine.entity;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * One VINE entity as data (sub-08 Stage A): identity, attributes, physical size and
 * (from Stage D) its parts. Entity types are <em>structural</em> content — they
 * register statically at startup through sub-02's structural path and never ride a
 * hot-reloadable dynamic registry, because a native entity type cannot be
 * redefined under a running world. Design data (behaviour presets, stats) is what
 * may hot-reload, and it does so through design descriptors, not through this one.
 *
 * <p>The cell materializes one native entity kind per descriptor and binds the
 * attributes here to that cell's own attribute store, so the numbers a
 * Monster-Hunter-shaped fight depends on (health, speed, knockback resistance,
 * part-driving reach) are authored once and mean the same thing everywhere. The
 * parts themselves are declared here and hosted from Stage D onward, when the pose
 * evaluator can say where their bones are.
 *
 * <p><b>Invariants:</b> attribute ids are unique (two specs for one attribute would
 * fight invisibly) and part names are unique (a part's state is keyed by its name).
 * Immutable; equality is identity-of-fields, never of native state.
 *
 * @param id         the entity's engine id; also its native registry key
 * @param attributes the attributes a fresh entity starts with, in declaration order
 * @param dimensions the entity's physical size
 * @param parts      the entity's parts (empty for a single-collider entity; hosted
 *                   from Stage D onward)
 */
public record EntityDescriptor(VineId id, List<AttributeSpec> attributes, Dimensions dimensions,
        List<PartDescriptor> parts) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<EntityDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(EntityDescriptor::id),
        AttributeSpec.CODEC.listOf().optionalFieldOf("attributes", List.of()).forGetter(EntityDescriptor::attributes),
        Dimensions.CODEC.fieldOf("dimensions").forGetter(EntityDescriptor::dimensions),
        PartDescriptor.CODEC.listOf().optionalFieldOf("parts", List.of()).forGetter(EntityDescriptor::parts)
    ).apply(instance, EntityDescriptor::new));

    /** A single-collider entity with no parts — the Stage-A shape for plain mobs. */
    public static EntityDescriptor of(VineId id, Dimensions dimensions, AttributeSpec... attributes) {
        return new EntityDescriptor(id, List.of(attributes), dimensions, List.of());
    }

    public EntityDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimensions, "dimensions");
        attributes = List.copyOf(Objects.requireNonNull(attributes, "attributes"));
        parts = List.copyOf(Objects.requireNonNull(parts, "parts"));
        HashSet<VineId> attributeIds = new HashSet<>(attributes.size());
        for (AttributeSpec attribute : attributes) {
            Objects.requireNonNull(attribute, "attributes element");
            if (!attributeIds.add(attribute.attribute())) {
                throw new IllegalArgumentException("EntityDescriptor " + id + ": attribute "
                    + attribute.attribute() + " is declared twice — two specs for one attribute cannot be ordered");
            }
        }
        HashSet<String> partNames = new HashSet<>(parts.size());
        for (PartDescriptor part : parts) {
            Objects.requireNonNull(part, "parts element");
            if (!partNames.add(part.name())) {
                throw new IllegalArgumentException("EntityDescriptor " + id + ": part '" + part.name()
                    + "' is declared twice — a part's state is keyed by its name");
            }
        }
    }
}
