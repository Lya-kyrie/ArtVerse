package com.artverse.agent.gateway;

import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Thread-safe, size-and-TTL bounded cache for heavyweight {@link AutoCloseable} resources.
 * <p>
 * Eviction policies:
 * <ul>
 *   <li><b>TTL eviction</b> — entries idle longer than {@code idleTimeoutMillis} are removed on access
 *       and during periodic background sweeps.</li>
 *   <li><b>Capacity eviction</b> — when the cache exceeds {@code maxSize}, the oldest entry
 *       (by creation time) is evicted before adding a new one.</li>
 * </ul>
 * <p>
 * Evicted resources have their {@link AutoCloseable#close()} method called.
 *
 * @param <T> the resource type, must implement {@link AutoCloseable}
 */
@Slf4j
public class AgentCache<T extends AutoCloseable> {

    private final ConcurrentHashMap<String, Entry<T>> cache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    private final int maxSize;
    private final long idleTimeoutMillis;

    private final AtomicLong evictionCount = new AtomicLong(0);

    /**
     * @param maxSize            maximum number of cached agents (soft bound)
     * @param idleTimeoutMinutes  evict agents not accessed for this many minutes
     * @param cleanupIntervalMinutes  interval for background eviction sweep
     */
    public AgentCache(int maxSize, int idleTimeoutMinutes, int cleanupIntervalMinutes) {
        this.maxSize = maxSize;
        this.idleTimeoutMillis = TimeUnit.MINUTES.toMillis(idleTimeoutMinutes);

        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agent-cache-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(
                this::evictExpiredEntries,
                cleanupIntervalMinutes,
                cleanupIntervalMinutes,
                TimeUnit.MINUTES
        );
        log.info("AgentCache initialized: maxSize={}, idleTimeout={}min, cleanupInterval={}min",
                maxSize, idleTimeoutMinutes, cleanupIntervalMinutes);
    }

    /**
     * Get an existing cached agent, or create and cache a new one via {@code factory}.
     * <p>
     * Expired entries are evicted on access. If the cache is at capacity, the oldest entry
     * is evicted before inserting a new one.
     *
     * @param key     cache key (typically from {@link AgentScopeAgentFactory#buildAgentCacheKey})
     * @param factory function to create a new agent when no valid cached entry exists
     * @return the cached or newly-created agent
     */
    public T getOrCreate(String key, Function<String, T> factory) {
        // 1. Fast path: valid cached entry
        Entry<T> entry = cache.get(key);
        if (entry != null) {
            if (!entry.isExpired(idleTimeoutMillis)) {
                entry.touch();
                return entry.agent;
            }
            // Expired — evict
            evict(key, entry, "TTL expired");
        }

        // 2. Capacity check: soft bound, best-effort. Retry once if the first
        //    eviction was a no-op (entry concurrently removed by another thread).
        if (cache.size() >= maxSize) {
            if (!evictOldest() && cache.size() >= maxSize) {
                evictOldest();
            }
        }

        // 3. Create and cache
        T agent = factory.apply(key);
        Entry<T> newEntry = new Entry<>(agent);
        Entry<T> race = cache.putIfAbsent(key, newEntry);
        if (race != null) {
            // Lost the race — another thread created the same agent first
            closeAgent(agent);
            race.touch();
            return race.agent;
        }

        log.debug("Agent cached: key={}, cacheSize={}", key, cache.size());
        return agent;
    }

    /**
     * Remove an entry from the cache using value-guarded removal. Safe to call from any thread.
     *
     * @return true if the entry was successfully removed and closed, false if another thread
     *         already removed it
     */
    private boolean evict(String key, Entry<T> entry, String reason) {
        boolean removed = cache.remove(key, entry);
        if (removed) {
            evictionCount.incrementAndGet();
            log.info("Agent evicted: key={}, reason={}, cacheSize={}, totalEvictions={}",
                    key, reason, cache.size(), evictionCount.get());
            closeAgent(entry.agent);
        }
        return removed;
    }

    /**
     * Evict the oldest entry by creation time. Called when cache is at capacity.
     *
     * @return true if an entry was actually evicted, false if no entry could be removed
     *         (e.g. the oldest entry was concurrently removed or touched by another thread)
     */
    private boolean evictOldest() {
        String oldestKey = null;
        Entry<T> oldestEntry = null;
        long oldestTime = Long.MAX_VALUE;

        for (Map.Entry<String, Entry<T>> e : cache.entrySet()) {
            if (e.getValue().createdAtNanos < oldestTime) {
                oldestTime = e.getValue().createdAtNanos;
                oldestKey = e.getKey();
                oldestEntry = e.getValue();
            }
        }

        if (oldestKey != null && oldestEntry != null) {
            return evict(oldestKey, oldestEntry, "capacity (" + maxSize + ")");
        }
        return false;
    }

    /**
     * Background sweep: remove all expired entries.
     */
    private void evictExpiredEntries() {
        int removed = 0;
        for (Map.Entry<String, Entry<T>> e : cache.entrySet()) {
            if (e.getValue().isExpired(idleTimeoutMillis)) {
                if (cache.remove(e.getKey(), e.getValue())) {
                    evictionCount.incrementAndGet();
                    closeAgent(e.getValue().agent);
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.info("Background sweep evicted {} expired agents, cacheSize={}, totalEvictions={}",
                    removed, cache.size(), evictionCount.get());
        }
    }

    /**
     * Safely close an agent, catching any exceptions. Called on eviction.
     */
    private void closeAgent(T agent) {
        try {
            agent.close();
        } catch (Exception e) {
            log.warn("Failed to close evicted agent: {}", e.getMessage());
        }
    }

    /**
     * Destroy the cache: shut down the background cleaner, atomically drain and close
     * all remaining cached agents, and clear the map. Intended for {@code @PreDestroy}.
     * <p>
     * Uses value-guarded removal to drain — entries concurrently removed by the
     * (shutting-down) sweeper thread are skipped to avoid double-close.
     */
    public void destroy() {
        log.info("Destroying AgentCache: {} agents cached, {} total evictions",
                cache.size(), evictionCount.get());

        cleaner.shutdownNow();
        try {
            if (!cleaner.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Agent cache cleaner did not terminate within 5 seconds");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Atomic drain: only close entries we successfully remove ourselves.
        // This avoids double-close if the sweeper thread removed+closed an entry
        // before it was fully shut down.
        Iterator<Map.Entry<String, Entry<T>>> it = cache.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry<T>> entry = it.next();
            if (cache.remove(entry.getKey(), entry.getValue())) {
                closeAgent(entry.getValue().agent);
            }
        }
        log.info("AgentCache destroyed");
    }

    /**
     * Current number of cached entries.
     */
    public int size() {
        return cache.size();
    }

    /**
     * Total number of evictions since this cache was created.
     */
    public long evictionCount() {
        return evictionCount.get();
    }

    // ---- internal entry ----

    private static class Entry<T extends AutoCloseable> {
        final T agent;
        final AtomicLong lastAccessNanos;
        final long createdAtNanos;

        Entry(T agent) {
            long now = System.nanoTime();
            this.agent = agent;
            this.lastAccessNanos = new AtomicLong(now);
            this.createdAtNanos = now;
        }

        void touch() {
            lastAccessNanos.set(System.nanoTime());
        }

        boolean isExpired(long idleTimeoutMillis) {
            long idleNanos = System.nanoTime() - lastAccessNanos.get();
            return TimeUnit.NANOSECONDS.toMillis(idleNanos) > idleTimeoutMillis;
        }
    }
}
