package com.artverse.agent.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentCacheTest {

    private AgentCache<StubResource> cache;

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.destroy();
        }
    }

    private AgentCache<StubResource> createCache(int maxSize, int idleTimeoutMinutes) {
        return new AgentCache<>(maxSize, idleTimeoutMinutes, 60); // cleanup rarely in tests
    }

    private static StubResource stub(AtomicBoolean closed) {
        return new StubResource(closed);
    }

    /**
     * Simple AutoCloseable stub that records whether close() was called.
     */
    private static class StubResource implements AutoCloseable {
        private final AtomicBoolean closed;

        StubResource(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    void getOrCreate_createsOnMiss() {
        cache = createCache(10, 30);
        AtomicInteger factoryCalls = new AtomicInteger(0);

        StubResource agent = cache.getOrCreate("key1", k -> {
            factoryCalls.incrementAndGet();
            return stub(new AtomicBoolean());
        });

        assertNotNull(agent);
        assertEquals(1, factoryCalls.get());
        assertEquals(1, cache.size());
    }

    @Test
    void getOrCreate_returnsCachedOnHit() {
        cache = createCache(10, 30);
        AtomicInteger factoryCalls = new AtomicInteger(0);
        AtomicBoolean closed = new AtomicBoolean(false);

        StubResource first = cache.getOrCreate("key1", k -> {
            factoryCalls.incrementAndGet();
            return stub(closed);
        });
        StubResource second = cache.getOrCreate("key1", k -> {
            factoryCalls.incrementAndGet();
            return stub(new AtomicBoolean());
        });

        assertSame(first, second);
        assertEquals(1, factoryCalls.get());
        assertEquals(1, cache.size());
        assertFalse(closed.get(), "agent should not be closed on cache hit");
    }

    @Test
    void expiredEntry_evictedAndRecreated() throws InterruptedException {
        cache = createCache(10, 0); // 0-minute TTL: expires immediately
        AtomicInteger factoryCalls = new AtomicInteger(0);
        AtomicBoolean firstClosed = new AtomicBoolean(false);

        StubResource first = cache.getOrCreate("key1", k -> {
            factoryCalls.incrementAndGet();
            return stub(firstClosed);
        });
        assertEquals(1, factoryCalls.get());

        // TTL is 0, so any access should see it as expired after a tiny clock tick
        TimeUnit.MILLISECONDS.sleep(1);

        StubResource second = cache.getOrCreate("key1", k -> {
            factoryCalls.incrementAndGet();
            return stub(new AtomicBoolean());
        });

        assertEquals(2, factoryCalls.get());
        assertNotSame(first, second);
        assertTrue(firstClosed.get(), "expired agent should be closed on eviction");
        assertEquals(1, cache.size());
    }

    @Test
    void capacityExceeded_evictsOldest() {
        cache = createCache(2, 60);

        AtomicBoolean closed1 = new AtomicBoolean(false);
        AtomicBoolean closed2 = new AtomicBoolean(false);
        AtomicBoolean closed3 = new AtomicBoolean(false);

        cache.getOrCreate("key1", k -> stub(closed1));
        cache.getOrCreate("key2", k -> stub(closed2));

        assertEquals(2, cache.size());

        // Third entry triggers capacity eviction — oldest ("key1") should go
        cache.getOrCreate("key3", k -> stub(closed3));

        assertEquals(2, cache.size());
        assertTrue(closed1.get(), "oldest entry should be evicted");
        assertFalse(closed2.get(), "newer entry should remain");
        assertFalse(closed3.get(), "just-added entry should remain");

        // key2 should still be accessible (verify factory not called)
        AtomicInteger factoryCalls = new AtomicInteger(0);
        StubResource k2 = cache.getOrCreate("key2", k -> {
            factoryCalls.incrementAndGet();
            return stub(new AtomicBoolean());
        });
        assertNotNull(k2);
        assertEquals(0, factoryCalls.get(), "factory should not be called for existing key2");
        assertEquals(2, cache.size());
    }

    @Test
    void destroy_closesAllAgents() {
        cache = createCache(10, 60);

        AtomicBoolean closed1 = new AtomicBoolean(false);
        AtomicBoolean closed2 = new AtomicBoolean(false);
        AtomicBoolean closed3 = new AtomicBoolean(false);

        cache.getOrCreate("key1", k -> stub(closed1));
        cache.getOrCreate("key2", k -> stub(closed2));
        cache.getOrCreate("key3", k -> stub(closed3));

        assertEquals(3, cache.size());

        cache.destroy();

        assertEquals(0, cache.size());
        assertTrue(closed1.get(), "all agents should be closed on destroy");
        assertTrue(closed2.get(), "all agents should be closed on destroy");
        assertTrue(closed3.get(), "all agents should be closed on destroy");
    }

    @Test
    void raceCondition_duplicateCreationOnlyOneSurvives() throws Exception {
        cache = createCache(10, 60);

        int threadCount = 4;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger factoryCalls = new AtomicInteger(0);
        AtomicInteger closedCount = new AtomicInteger(0);
        StubResource[] results = new StubResource[threadCount];

        var executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    results[index] = cache.getOrCreate("same-key", k -> {
                        factoryCalls.incrementAndGet();
                        return new StubResource(new AtomicBoolean()) {
                            @Override
                            public void close() {
                                super.close();
                                closedCount.incrementAndGet();
                            }
                        };
                    });
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        // All threads should get the same instance
        StubResource survivor = results[0];
        assertNotNull(survivor);
        for (int i = 1; i < threadCount; i++) {
            assertSame(survivor, results[i],
                    "all threads should receive the same cached agent");
        }

        // Exactly one agent survives in cache
        assertEquals(1, cache.size());

        // factoryCalls >= 1 (possibly more due to race)
        assertTrue(factoryCalls.get() >= 1);

        // closed agents = factoryCalls - 1 (all losers closed)
        assertEquals(factoryCalls.get() - 1, closedCount.get());
    }

    @Test
    void evictionCount_incrementsOnEviction() {
        cache = createCache(1, 60);
        AtomicBoolean closed1 = new AtomicBoolean(false);

        cache.getOrCreate("key1", k -> stub(closed1));
        assertEquals(0, cache.evictionCount());

        cache.getOrCreate("key2", k -> stub(new AtomicBoolean()));
        assertEquals(1, cache.evictionCount());
        assertTrue(closed1.get());
    }
}
