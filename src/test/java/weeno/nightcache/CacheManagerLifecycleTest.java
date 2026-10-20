package weeno.nightcache;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import weeno.nightcache.caches.AsyncLeaderboard;
import weeno.nightcache.interfaces.Managed;
import weeno.nightcache.interfaces.ObjectPool;
import weeno.nightcache.objects.CacheSpec;
import weeno.nightcache.objects.CacheStats;
import weeno.nightcache.platform.Platform;
import weeno.nightcache.testsupport.FakeServer;
import weeno.nightcache.testsupport.Threads;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cycle de vie du registre des caches et du thread des timers : rien ne survit au shutdown. */
class CacheManagerLifecycleTest {

    private static Plugin plugin;

    @BeforeAll
    static void server() {
        FakeServer.install();
        plugin = FakeServer.plugin("Caches");
    }

    @AfterEach
    void cleanup() {
        CacheManager.shutdown();
        Platform.shutdown();
    }

    @Test
    void shutdownStopsTheTimerThreadWithoutPlatformShutdown() throws Exception {
        // Le janitor et les leaderboards async tournent sur le thread démon de la lib : CacheManager.shutdown()
        // seul (sans NightCache.disable) laissait ce thread vivant pour toujours.
        CacheManager.init(plugin);
        AsyncLeaderboard<String> lb = CacheManager.asyncLeaderboard("top", 5, 1);
        lb.add("a", 3);
        assertFalse(Threads.nightCache().isEmpty(), "thread des timers démarré");
        CacheManager.shutdown();
        List<String> alive = Threads.awaitNone(3_000);
        assertTrue(alive.isEmpty(), "threads NightCache encore vivants : " + alive);
    }

    @Test
    void timerThreadRestartsAfterAnIdleStop() throws Exception {
        CacheManager.init(plugin);
        CacheManager.shutdown();
        assertTrue(Threads.awaitNone(3_000).isEmpty());
        CacheManager.init(plugin);
        AsyncLeaderboard<String> lb = CacheManager.asyncLeaderboard("top", 5, 1);
        lb.set("a", 7);
        long end = System.currentTimeMillis() + 5_000;
        while (lb.getTop().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertEquals(7, lb.getTop().get(0).score(), "classement rafraîchi après redémarrage");
    }

    @Test
    void asyncLeaderboardCreatedBeforeInitIsStartedByInit() throws Exception {
        // Créé avant init (plugin inconnu), il n'était jamais démarré : top vide pour toujours.
        AsyncLeaderboard<String> lb = CacheManager.asyncLeaderboard("early", 5, 1);
        lb.set("a", 42);
        CacheManager.init(plugin);
        long end = System.currentTimeMillis() + 5_000;
        while (lb.getTop().isEmpty() && System.currentTimeMillis() < end) Thread.sleep(10);
        assertEquals(1, lb.getTop().size(), "classement créé avant init jamais rafraîchi");
        assertEquals(42, lb.getTop().get(0).score());
    }

    @Test
    void shutdownEmptiesEveryRegistryEvenIfACacheThrows() {
        CacheManager.init(plugin);
        CacheManager.cache(CacheSpec.bounded("objets", 10)).put("k", "v");
        CacheManager.longCache("longs", 10, 0).put(1L, "v");
        CacheManager.register(new Exploding("explosif"));
        CacheManager.objectLongCache("compteurs", 10, 0, 0).addTo("k", 1);
        CacheManager.leaderboard("classement").set("k", 1);
        ObjectPool<StringBuilder> pool = CacheManager.pool("tampons", StringBuilder::new, 4);
        pool.release(new StringBuilder());
        CacheManager.asyncLeaderboard("async", 3, 1);
        CacheManager.shutdown();
        assertEquals(0, CacheManager.cacheCount(), "caches restés enregistrés après une erreur");
        assertEquals(0, CacheManager.poolCount());
        assertEquals(0, CacheManager.asyncLeaderboardCount());
        assertEquals(0, pool.available(), "pool vidé");
        assertNull(CacheManager.plugin(), "plus de référence au plugin");
        assertTrue(CacheManager.isShuttingDown());
    }

    @Test
    void initIsIdempotentAndRegistryReturnsTheSameInstances() {
        CacheManager.init(plugin);
        CacheManager.init(plugin);
        assertEquals(CacheManager.longCache("x", 1, 0), CacheManager.<String>longCache("x", 1, 0));
        assertEquals(1, CacheManager.cacheCount());
        assertEquals(1, Threads.nightCache().size(), "un seul thread de timers par plugin");
        assertFalse(CacheManager.isShuttingDown());
    }

    @Test
    void janitorSurvivesAFailingCache() throws Exception {
        AtomicInteger cleaned = new AtomicInteger();
        CacheManager.register(new Exploding("a"));
        CacheManager.register(new Counting("b", cleaned));
        java.lang.reflect.Method tick = CacheManager.class.getDeclaredMethod("janitorTick");
        tick.setAccessible(true);
        tick.invoke(null);
        tick.invoke(null);
        assertEquals(2, cleaned.get(), "une purge en erreur n'empêche pas les suivantes");
        assertEquals(1, CacheManager.stats().size(), "stats() ignore un cache en erreur au lieu de lever");
    }

    /** Cache spécialisé dont toutes les opérations échouent. */
    private record Exploding(String name) implements Managed {
        @Override public long estimatedSize() { return 0; }
        @Override public void invalidateAll() { throw new IllegalStateException("invalidateAll"); }
        @Override public void cleanUp() { throw new IllegalStateException("cleanUp"); }
        @Override public CacheStats stats() { throw new IllegalStateException("stats"); }
    }

    private record Counting(String name, AtomicInteger cleaned) implements Managed {
        @Override public long estimatedSize() { return 0; }
        @Override public void invalidateAll() {}
        @Override public void cleanUp() { cleaned.incrementAndGet(); }
        @Override public CacheStats stats() { return new CacheStats(name, 0, 0, 0, 0); }
    }
}
