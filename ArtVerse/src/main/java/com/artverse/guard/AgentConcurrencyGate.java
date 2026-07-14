package com.artverse.guard;

import com.artverse.common.BusinessException;
import com.artverse.config.ArtVerseProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis-backed global and per-user run gate with renewable leases. */
@Component
public class AgentConcurrencyGate {

    private static final String BUSY = "Agent concurrency quota exceeded; please retry later";
    private static final String GLOBAL_KEY = "artverse:agent:concurrency:global";
    private static final Duration LEASE = Duration.ofSeconds(90);

    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', ARGV[1])
            if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 0 end
            if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[4]) then return 0 end
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[5])
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[5])
            redis.call('EXPIRE', KEYS[1], ARGV[6])
            redis.call('EXPIRE', KEYS[2], ARGV[6])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREM', KEYS[1], ARGV[1])
            redis.call('ZREM', KEYS[2], ARGV[1])
            return 1
            """, Long.class);
    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('ZSCORE', KEYS[1], ARGV[1]) and redis.call('ZSCORE', KEYS[2], ARGV[1]) then
              redis.call('ZADD', KEYS[1], 'XX', ARGV[2], ARGV[1])
              redis.call('ZADD', KEYS[2], 'XX', ARGV[2], ARGV[1])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final int globalLimit;
    private final int perUserLimit;
    private final Semaphore localFallback;
    private final ScheduledExecutorService renewer;
    private final ConcurrentLinkedQueue<Permit> legacyPermits = new ConcurrentLinkedQueue<>();

    @Autowired
    public AgentConcurrencyGate(StringRedisTemplate redisTemplate, ArtVerseProperties properties) {
        this.redisTemplate = redisTemplate;
        this.globalLimit = Math.max(1, properties.getAgent().getMaxConcurrentRuns());
        this.perUserLimit = Math.max(1, properties.getAgent().getMaxConcurrentRunsPerUser());
        this.localFallback = null;
        this.renewer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-concurrency-renewer");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Test/standalone compatibility constructor. Production uses Redis. */
    public AgentConcurrencyGate(ArtVerseProperties properties) {
        this.redisTemplate = null;
        this.globalLimit = Math.max(1, properties.getAgent().getMaxConcurrentRuns());
        this.perUserLimit = Math.max(1, properties.getAgent().getMaxConcurrentRunsPerUser());
        this.localFallback = new Semaphore(globalLimit);
        this.renewer = null;
    }

    public Permit acquireOrReject(Long userId, UUID requestId) {
        if (redisTemplate == null) {
            if (!localFallback.tryAcquire()) throw new BusinessException(429, BUSY);
            return new Permit("local-" + UUID.randomUUID(), "local", true);
        }
        if (userId == null || requestId == null) {
            throw new BusinessException(400, "userId and requestId are required for agent concurrency control");
        }
        String userKey = userKey(userId);
        String token = userId + ":" + requestId + ":" + UUID.randomUUID();
        long now = System.currentTimeMillis();
        Long acquired;
        try {
            acquired = redisTemplate.execute(ACQUIRE_SCRIPT, List.of(GLOBAL_KEY, userKey),
                    String.valueOf(now), String.valueOf(now + LEASE.toMillis()),
                    String.valueOf(globalLimit), String.valueOf(perUserLimit), token,
                    String.valueOf(LEASE.toSeconds() * 2));
        } catch (RuntimeException error) {
            throw new BusinessException(503, "Agent concurrency service is unavailable");
        }
        if (!Long.valueOf(1).equals(acquired)) throw new BusinessException(429, BUSY);
        Permit permit = new Permit(token, userKey, false);
        ScheduledFuture<?> renewal = renewer.scheduleAtFixedRate(
                () -> renew(permit), 30, 30, TimeUnit.SECONDS);
        permit.renewal = renewal;
        return permit;
    }

    public void release(Permit permit) {
        if (permit == null || !permit.released.compareAndSet(false, true)) return;
        if (permit.renewal != null) permit.renewal.cancel(false);
        if (permit.local) {
            localFallback.release();
            return;
        }
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(GLOBAL_KEY, permit.userKey), permit.token);
        } catch (RuntimeException ignored) {
            // Lease expiry guarantees eventual recovery when Redis is temporarily unavailable.
        }
    }

    /** Legacy compatibility API; new code should retain and release the returned Permit. */
    public void acquireOrReject() {
        Permit permit = acquireOrReject(0L, UUID.randomUUID());
        legacyPermits.add(permit);
    }

    public void release() {
        release(legacyPermits.poll());
    }

    public int availablePermits() {
        if (redisTemplate == null) return localFallback.availablePermits();
        Long active = redisTemplate.opsForZSet().zCard(GLOBAL_KEY);
        return Math.max(0, globalLimit - (active == null ? 0 : active.intValue()));
    }

    private void renew(Permit permit) {
        if (permit.released.get()) return;
        try {
            long expiry = System.currentTimeMillis() + LEASE.toMillis();
            redisTemplate.execute(RENEW_SCRIPT, List.of(GLOBAL_KEY, permit.userKey),
                    permit.token, String.valueOf(expiry));
        } catch (RuntimeException ignored) {
            // A later renewal may recover; an expired lease is never trusted for writes.
        }
    }

    private String userKey(Long userId) {
        return "artverse:agent:concurrency:user:" + userId;
    }

    @PreDestroy
    void shutdown() {
        if (renewer != null) renewer.shutdownNow();
    }

    public static final class Permit {
        private final String token;
        private final String userKey;
        private final boolean local;
        private final AtomicBoolean released = new AtomicBoolean(false);
        private volatile ScheduledFuture<?> renewal;

        private Permit(String token, String userKey, boolean local) {
            this.token = token;
            this.userKey = userKey;
            this.local = local;
        }
    }
}
