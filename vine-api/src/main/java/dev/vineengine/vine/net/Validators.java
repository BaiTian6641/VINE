package dev.vineengine.vine.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.vineengine.vine.VineConfig;
import dev.vineengine.vine.VinePlayer;

/**
 * Ready-made validators (sub-05 Stage D). {@link #rateLimit()} reads the
 * engine's configured defaults; the sized overloads are for channels that need
 * their own budget.
 *
 * <p>The limiter is per-player and per-validator-instance (one bucket per
 * player that ever sent to the message it guards); buckets are fixed-size token
 * counts, so a flood can never grow engine memory.
 */
public final class Validators {

    /** Config keys backing {@link #rateLimit()}. */
    public static final String CONFIG_PER_SECOND = "net.rateLimit.perSecond";
    public static final String CONFIG_BURST = "net.rateLimit.burst";

    private Validators() {
    }

    /**
     * Token-bucket rate limiter using the engine's configured defaults
     * ({@value #CONFIG_PER_SECOND} / {@value #CONFIG_BURST}).
     */
    public static <P> Validator<P> rateLimit() {
        return rateLimit(VineConfig.getInt(CONFIG_PER_SECOND, 20), VineConfig.getInt(CONFIG_BURST, 40));
    }

    /** Token-bucket rate limiter: {@code perSecond} refill, {@code burst} capacity. */
    public static <P> Validator<P> rateLimit(int perSecond, int burst) {
        if (perSecond <= 0 || burst <= 0) {
            throw new IllegalArgumentException("rate limit needs perSecond/burst > 0 (" + perSecond + "/" + burst + ")");
        }
        return new TokenBucket<>(perSecond, burst);
    }

    private static final class TokenBucket<P> implements Validator<P> {

        private final int perSecond;
        private final int burst;
        private final Map<UUID, Bucket> buckets = new ConcurrentHashMap<>();

        TokenBucket(int perSecond, int burst) {
            this.perSecond = perSecond;
            this.burst = burst;
        }

        @Override
        public Verdict validate(P payload, VinePlayer sender) {
            Bucket bucket = buckets.computeIfAbsent(sender.uniqueId(),
                id -> new Bucket(burst, System.nanoTime(), burst));
            synchronized (bucket) {
                long now = System.nanoTime();
                long elapsedNanos = now - bucket.lastRefillNanos;
                long refill = elapsedNanos * perSecond / 1_000_000_000L;
                if (refill > 0) {
                    bucket.tokens = Math.min(burst, bucket.tokens + (int) refill);
                    bucket.lastRefillNanos = now;
                }
                if (bucket.tokens > 0) {
                    bucket.tokens--;
                    return Verdict.ACCEPT;
                }
                return Verdict.REJECT;
            }
        }

        private static final class Bucket {

            private int tokens;
            private long lastRefillNanos;

            Bucket(int tokens, long lastRefillNanos, int burst) {
                this.tokens = tokens;
                this.lastRefillNanos = lastRefillNanos;
            }
        }
    }
}
