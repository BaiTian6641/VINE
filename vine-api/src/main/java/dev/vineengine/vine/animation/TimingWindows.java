package dev.vineengine.vine.animation;

import java.util.List;

/**
 * The gameplay windows of one clip (sub-09 §2): startup, active, recovery and the
 * combo-cancel point, in <em>server ticks</em> at 20 TPS. Animation is data driving
 * combat timing — this is the type that carries that sentence into sub-10's pipeline.
 *
 * <p>The windows come from the clip's own markers under VINE's namespace
 * ({@code vine:active_start}, {@code vine:active_end}, {@code vine:cancel_start}), so
 * an author states the timing once, in the asset, next to the motion it belongs to,
 * and both the evaluator and any client backend read the same statement. A clip with
 * no such markers yields windows that cover the whole clip as recovery, which is the
 * honest reading of "nobody declared a hit window here".
 *
 * @param startupTicks the wind-up, before the attack can connect
 * @param activeTicks  how long the attack can connect for
 * @param cancelTicks  ticks after which the clip may be cancelled into another action
 * @param totalTicks   the clip's full length
 */
public record TimingWindows(int startupTicks, int activeTicks, int cancelTicks, int totalTicks) {

    public TimingWindows {
        if (startupTicks < 0 || activeTicks < 0 || totalTicks < startupTicks + activeTicks) {
            throw new IllegalArgumentException("TimingWindows must describe a real clip: startup=" + startupTicks
                + " active=" + activeTicks + " total=" + totalTicks);
        }
        if (cancelTicks < 0) {
            cancelTicks = startupTicks + activeTicks;
        }
    }

    /** The first tick an attack can connect on. */
    public int activeStartTick() {
        return startupTicks;
    }

    /** The first tick an attack can no longer connect on (exclusive). */
    public int activeEndTick() {
        return startupTicks + activeTicks;
    }

    /** The recovery, in ticks, between the end of the active window and the clip's end. */
    public int recoveryTicks() {
        return Math.max(0, totalTicks - activeEndTick());
    }

    /** Whether tick {@code tick} is inside the active window. */
    public boolean isActive(int tick) {
        return tick >= activeStartTick() && tick < activeEndTick();
    }

    /** Whether the clip may be cancelled into another action at {@code tick}. */
    public boolean cancellable(int tick) {
        return tick >= cancelTicks;
    }

    /** The four windows as named spans, for traces and fixtures. */
    public List<Window> asWindows() {
        return List.of(
            new Window("startup", 0, startupTicks),
            new Window("active", activeStartTick(), activeEndTick()),
            new Window("recovery", activeEndTick(), totalTicks),
            new Window("cancel", cancelTicks, totalTicks));
    }

    /** One named half-open span of ticks. */
    public record Window(String phase, int fromTick, int toTick) {
    }
}
