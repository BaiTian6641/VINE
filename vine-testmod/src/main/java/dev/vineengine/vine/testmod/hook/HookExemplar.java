package dev.vineengine.vine.testmod.hook;

import java.util.concurrent.atomic.AtomicInteger;

import dev.vineengine.vine.EventBus;
import dev.vineengine.vine.EventPriority;
import dev.vineengine.vine.Subscription;
import dev.vineengine.vine.VineEngine;
import dev.vineengine.vine.hook.HookEvents;

/**
 * Live hook-slot assertion (sub-01 Stage C acceptance): zero-consumer boot means
 * zero native listeners; first subscribe installs, last close uninstalls; a
 * cancelled pre-event gates the remaining handlers and stays observable on the
 * returned event. One printed line per check for the {@code vine_test:hooks}
 * TCK scenario.
 */
public final class HookExemplar {

    private static final AtomicInteger OBSERVED = new AtomicInteger();

    private HookExemplar() {
    }

    /** Command entry: Minimal Footprint counter transitions + hook subscriptions. */
    public static void subscribe() {
        EventBus bus = VineEngine.get().events();
        System.out.println("vine-testmod: hooks native=" + bus.activeHookInstalls());

        Subscription nativeSub = bus.subscribe(HookEvents.BlockBreak.class, e -> { });
        System.out.println("vine-testmod: hooks after-subscribe native=" + bus.activeHookInstalls());
        nativeSub.close();
        System.out.println("vine-testmod: hooks after-close native=" + bus.activeHookInstalls());

        // Cancellable pre-event: FIRST cancels the synthetic "cancel" input, the
        // NORMAL observer counts what actually reached it.
        bus.subscribe(HookEvents.CommandExecute.class, EventPriority.FIRST, e -> {
            System.out.println("vine-testmod: hook veto-check input=" + e.input());
            if ("synthetic-cancel".equals(e.input())) {
                e.cancel();
            }
        });
        bus.subscribe(HookEvents.CommandExecute.class, e -> OBSERVED.incrementAndGet());
        bus.subscribe(HookEvents.PacketReceive.class, e ->
            System.out.println("vine-testmod: hook packet channel=" + e.channelId()));
        System.out.println("vine-testmod: hooks subscribed");
    }

    /** Command entry: posts synthetic pre-events and prints the observable outcomes. */
    public static void fire() {
        EventBus bus = VineEngine.get().events();
        OBSERVED.set(0);
        HookEvents.CommandExecute normal = bus.post(
            new HookEvents.CommandExecute("synthetic-normal", "console"));
        System.out.println("vine-testmod: hook post vetoed=" + normal.isCancelled());
        System.out.println("vine-testmod: hook observer count=" + OBSERVED.get());

        HookEvents.CommandExecute cancelled = bus.post(
            new HookEvents.CommandExecute("synthetic-cancel", "console"));
        System.out.println("vine-testmod: hook post vetoed=" + cancelled.isCancelled());
        System.out.println("vine-testmod: hook observer count=" + OBSERVED.get());
    }
}
