package dev.vineengine.vine.internal.driver1211.neoforge.net;

import java.util.concurrent.Executor;

import net.minecraft.client.Minecraft;

/**
 * Client-dist-only network helpers (sub-05 Stage A). Referenced exclusively from
 * code paths that run on a physical client — S2C inbound delivery and the
 * client-wired C2S sender — so the dedicated server never loads this class
 * (§2 dist segregation; NF strips {@code net.minecraft.client} there).
 */
public final class NeoForgeClientNetwork {

    private NeoForgeClientNetwork() {
    }

    /** The client main-thread executor for S2C inbound re-dispatch. */
    public static Executor mainThreadExecutor() {
        return Minecraft.getInstance();
    }
}
