package dev.vineengine.vine.combat;

import java.util.Objects;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.vineengine.vine.registry.VineId;

/**
 * An action as data (sub-10 Stage A): what a player or a creature may start doing, and
 * the windows that govern what may follow it.
 *
 * <p><b>Timing comes from the animation, never from this record.</b> Startup, active,
 * recovery and cancel windows are read from the sub-09 evaluator's own clip markers
 * ({@code vine:active_start} and friends); this descriptor carries only the numbers a
 * designer tunes — how long a request may wait in the buffer, how long after a hit a
 * combo may be continued, how long before the action may be used again.
 *
 * @param id              the action's engine id
 * @param clip            the animation clip this action plays, named the way clips are named
 *                        everywhere else ({@code animation.<namespace>.<asset>.<clip>}); the
 *                        clip's own markers are where this action's windows come from
 * @param bufferTicks     how long a request waits for the action to become available
 * @param comboWindowTicks how long after the action's end a follow-up stays a combo
 * @param cancelInto      the actions this one may be cancelled into
 * @param cooldownTicks   how long before this action may be used again
 */
public record ActionDescriptor(VineId id, String clip, int bufferTicks, int comboWindowTicks,
        Set<VineId> cancelInto, int cooldownTicks) {

    /** Single source of truth for every representation of this data (sub-02 §2). */
    public static final Codec<ActionDescriptor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        VineId.CODEC.fieldOf("id").forGetter(ActionDescriptor::id),
        Codec.STRING.fieldOf("animation").forGetter(ActionDescriptor::clip),
        Codec.INT.optionalFieldOf("bufferTicks", 0).forGetter(ActionDescriptor::bufferTicks),
        Codec.INT.optionalFieldOf("comboWindowTicks", 0).forGetter(ActionDescriptor::comboWindowTicks),
        VineId.CODEC.listOf().optionalFieldOf("cancelInto", java.util.List.of())
            .forGetter(descriptor -> java.util.List.copyOf(descriptor.cancelInto())),
        Codec.INT.optionalFieldOf("cooldownTicks", 0).forGetter(ActionDescriptor::cooldownTicks)
    ).apply(instance, (id, clip, buffer, combo, cancelInto, cooldown) -> new ActionDescriptor(id,
        clip, buffer, combo, Set.copyOf(cancelInto), cooldown)));

    public ActionDescriptor {
        Objects.requireNonNull(id, "id");
        if (clip == null || clip.isBlank()) {
            throw new IllegalArgumentException("ActionDescriptor " + id + " names no animation clip — its windows"
                + " have nowhere to come from");
        }
        cancelInto = Set.copyOf(Objects.requireNonNull(cancelInto, "cancelInto"));
        if (bufferTicks < 0 || comboWindowTicks < 0 || cooldownTicks < 0) {
            throw new IllegalArgumentException("ActionDescriptor " + id + ": window and cooldown ticks must be"
                + " non-negative, got buffer=" + bufferTicks + " combo=" + comboWindowTicks + " cooldown="
                + cooldownTicks);
        }
    }
}
