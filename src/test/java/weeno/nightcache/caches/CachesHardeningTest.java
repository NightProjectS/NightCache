package weeno.nightcache.caches;

import org.junit.jupiter.api.Test;
import weeno.nightcache.objects.LeaderboardEntry;
import weeno.nightcache.testsupport.Concurrency;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cas limites, modèles de référence aléatoires (graine fixe) et concurrence des caches primitifs. */
class CachesHardeningTest {

    // ─── Leaderboard ───────────────────────────────────────────────────────

    @Test
    void leaderboardWithoutWritesNeverRecomputesEvenWithZeroRefresh() {
        // refreshMs = 0 (valeur utilisée par AsyncLeaderboard) : le snapshot était recalculé à CHAQUE lecture,
        // génération incrémentée → AsyncLeaderboard republiait (et allouait) à chaque période sans aucune écriture.
        Leaderboard<String> lb = new Leaderboard<>("t", 0);
        for (int i = 0; i < 100; i++) lb.set("p" + i, i);
        List<LeaderboardEntry<String>> first = lb.top(10);
        int gen = lb.generation();
        for (int i = 0; i < 50; i++) assertSame(first, lb.top(10));
        lb.cleanUp(); // janitor
        assertEquals(gen, lb.generation(), "aucune écriture : aucune nouvelle génération");
        lb.add("p0", 1_000);
        assertEquals("p0", lb.top(1).get(0).key(), "une écriture est toujours visible immédiatement");
    }

    @Test
    void leaderboardMatchesReferenceModelUnderRandomOperations() {
        Random r = new Random(1234);
        Leaderboard<Integer> lb = new Leaderboard<>("t", r.nextBoolean() ? 0 : 60_000);
        Map<Integer, Long> model = new HashMap<>();
        for (int op = 0; op < 20_000; op++) {
            int k = r.nextInt(300);
            switch (r.nextInt(5)) {
                case 0 -> { long s = r.nextInt(2_000) - 1_000; lb.set(k, s); model.put(k, s); }
                case 1 -> { long d = r.nextInt(100) - 50; long v = lb.add(k, d); model.merge(k, d, Long::sum); assertEquals(model.get(k), v); }
                case 2 -> { lb.remove(k); model.remove(k); }
                case 3 -> assertEquals(model.getOrDefault(k, 0L), lb.score(k));
                default -> {
                    int n = r.nextInt(40);
                    List<LeaderboardEntry<Integer>> top = lb.top(n);
                    List<Long> expected = new ArrayList<>(model.values());
                    expected.sort(Collections.reverseOrder());
                    assertEquals(Math.min(Math.max(n, 0), expected.size()), top.size());
                    for (int i = 0; i < top.size(); i++) {
                        assertEquals(expected.get(i), top.get(i).score(), "tri respecté");
                        assertEquals(i + 1, top.get(i).rank());
                        assertEquals(model.get(top.get(i).key()), top.get(i).score());
                    }
                }
            }
        }
        assertEquals(model.size(), lb.estimatedSize());
        for (Map.Entry<Integer, Long> e : model.entrySet()) {
            long greater = model.values().stream().filter(v -> v > e.getValue()).count();
            long ties = model.values().stream().filter(v -> v.equals(e.getValue())).count();
            int rank = lb.rank(e.getKey());
            // hors top : 1 + scores strictement supérieurs ; dans le top, à égalité : position publiée
            assertTrue(rank >= greater + 1 && rank <= greater + ties, "rang " + rank + " hors de [" + (greater + 1) + ", " + (greater + ties) + "]");
        }
    }

    @Test
    void leaderboardHostileArguments() {
        Leaderboard<String> lb = new Leaderboard<>("t", 0);
        assertTrue(lb.top(0).isEmpty());
        assertTrue(lb.top(-5).isEmpty());
        assertTrue(lb.top(Integer.MAX_VALUE).isEmpty());
        lb.set("min", Long.MIN_VALUE);
        lb.set("max", Long.MAX_VALUE);
        lb.set("zéro 🎉", 0);
        List<LeaderboardEntry<String>> top = lb.top(Integer.MAX_VALUE);
        assertEquals(List.of("max", "zéro 🎉", "min"), List.of(top.get(0).key(), top.get(1).key(), top.get(2).key()));
        assertEquals(3, lb.rank("min"));
        lb.remove("absent");
        assertEquals(-1, lb.rank("absent"));
    }

    @Test
    void leaderboardConcurrentAddsConserveTheSum() throws Exception {
        Leaderboard<Integer> lb = new Leaderboard<>("t", 0);
        int threads = 8, ops = 5_000;
        Concurrency.run(threads, t -> {
            Random r = new Random(t);
            for (int i = 0; i < ops; i++) {
                lb.add(r.nextInt(50), 1);
                if (i % 100 == 0) lb.top(10);
            }
        });
        long sum = 0;
        for (LeaderboardEntry<Integer> e : lb.top(100)) sum += e.score();
        assertEquals((long) threads * ops, sum);
    }

    // ─── AsyncLeaderboard ──────────────────────────────────────────────────

    @Test
    void asyncLeaderboardDropsWritesOnceStopped() throws Exception {
        // Après stop() (disable), add/set s'empilaient sans fin dans une file que plus personne ne vidait.
        AsyncLeaderboard<String> lb = new AsyncLeaderboard<>("t", 10, 0, 1);
        lb.stop();
        for (int i = 0; i < 10_000; i++) lb.add("k" + (i % 10), 1);
        lb.set("x", 5);
        assertEquals(0, pending(lb).size(), "aucune écriture retenue par un classement arrêté");
    }

    @Test
    void asyncLeaderboardTopViewIsReusedWithoutAllocation() throws Exception {
        AsyncLeaderboard<Integer> lb = new AsyncLeaderboard<>("t", 100, 0, 1);
        for (int i = 0; i < 100; i++) lb.set(i, i);
        drain(lb);
        List<LeaderboardEntry<Integer>> a = lb.getTop(10);
        assertEquals(10, a.size());
        assertEquals(99, a.get(0).score());
        assertSame(a, lb.getTop(10), "même vue tant que le top publié ne change pas");
        assertEquals(1, lb.getRank(99));
        assertEquals(-1, lb.getRank(1_000));
        assertTrue(lb.getTop(-1).isEmpty());
        assertEquals(100, lb.getTop(1_000).size());
    }

    // ─── LongObjectCache ───────────────────────────────────────────────────

    @Test
    void longCacheReleasesPeakCapacityWithoutTtl() throws Exception {
        // Sans TTL, cleanUp() ne compactait jamais : un pic de 100 000 entrées gardait ses tableaux à vie.
        LongObjectCache<String> c = new LongObjectCache<>("t", 0, 0);
        for (long k = 0; k < 100_000; k++) c.put(k, "v");
        for (long k = 0; k < 99_990; k++) c.invalidate(k);
        c.cleanUp();
        assertTrue(capacity(c) <= 64, "capacité rendue après le pic : " + capacity(c));
        for (long k = 99_990; k < 100_000; k++) assertEquals("v", c.getIfPresent(k));
        assertEquals(10, c.estimatedSize());
    }

    @Test
    void longCacheMatchesLruReferenceModel() {
        Random r = new Random(42);
        int max = 64;
        LongObjectCache<Long> c = new LongObjectCache<>("t", max, 0);
        LinkedHashMap<Long, Long> model = new LinkedHashMap<>(16, 0.75f, true);
        for (int op = 0; op < 200_000; op++) {
            long k = r.nextInt(200) - 100L + (r.nextInt(10) == 0 ? Long.MIN_VALUE : 0L);
            switch (r.nextInt(6)) {
                case 0, 1 -> {
                    long v = r.nextLong();
                    c.put(k, v);
                    model.put(k, v);
                    if (model.size() > max) { Iterator<Long> it = model.keySet().iterator(); it.next(); it.remove(); }
                }
                case 2 -> { c.invalidate(k); model.remove(k); }
                case 3 -> assertEquals(model.get(k), c.getIfPresent(k));
                case 4 -> assertEquals(model.containsKey(k), c.containsKey(k));
                default -> { if (r.nextInt(500) == 0) c.cleanUp(); }
            }
            assertEquals(model.size(), c.estimatedSize());
        }
    }

    @Test
    void longCacheConcurrentAccessKeepsInvariants() throws Exception {
        LongObjectCache<Long> c = new LongObjectCache<>("t", 1_000, 0);
        Concurrency.run(8, t -> {
            Random r = new Random(t);
            for (int i = 0; i < 20_000; i++) {
                long k = r.nextInt(5_000);
                if (r.nextBoolean()) c.put(k, k);
                else {
                    Long v = c.getIfPresent(k);
                    if (v != null) assertEquals(k, v.longValue());
                }
                if (i % 1_000 == 0) c.cleanUp();
            }
        });
        assertTrue(c.estimatedSize() <= 1_000);
    }

    // ─── ObjectLongCache ───────────────────────────────────────────────────

    @Test
    void objectLongCacheMatchesReferenceModelWithBoundAndDefault() {
        Random r = new Random(7);
        int max = 32;
        ObjectLongCache<String> c = new ObjectLongCache<>("t", max, 0, -7);
        LinkedHashMap<String, Long> model = new LinkedHashMap<>(16, 0.75f, true);
        for (int op = 0; op < 100_000; op++) {
            String k = "k" + r.nextInt(80);
            switch (r.nextInt(4)) {
                case 0 -> { long v = r.nextLong(); c.put(k, v); model.put(k, v); }
                case 1 -> {
                    long d = r.nextInt(21) - 10;
                    long expected = model.getOrDefault(k, -7L) + d;
                    assertEquals(expected, c.addTo(k, d));
                    model.put(k, expected);
                }
                case 2 -> { c.invalidate(k); model.remove(k); }
                default -> {
                    Long m = model.get(k);
                    assertEquals(m == null ? -7L : m, c.getLong(k));
                }
            }
            if (model.size() > max) { Iterator<String> it = model.keySet().iterator(); it.next(); it.remove(); }
            assertEquals(model.size(), c.estimatedSize());
        }
    }

    @Test
    void objectLongCacheConcurrentAddToConservesTheSum() throws Exception {
        ObjectLongCache<Integer> c = new ObjectLongCache<>("t", 0, 0, 0);
        Concurrency.run(8, t -> {
            for (int i = 0; i < 10_000; i++) c.addTo(i % 16, 1);
        });
        long sum = 0;
        for (int k = 0; k < 16; k++) sum += c.getLong(k);
        assertEquals(80_000, sum);
    }

    // ─── BoundedPool ───────────────────────────────────────────────────────

    @Test
    void poolNeverLendsTheSameObjectTwiceConcurrently() throws Exception {
        BoundedPool<int[]> pool = BoundedPool.of(() -> new int[1], a -> a[0] = 0, 64);
        Set<int[]> lent = Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));
        AtomicInteger clashes = new AtomicInteger();
        Concurrency.run(8, t -> {
            for (int i = 0; i < 20_000; i++) {
                int[] a = pool.borrow();
                if (!lent.add(a)) clashes.incrementAndGet();
                if (a[0] != 0) clashes.incrementAndGet(); // reset non appliqué
                a[0] = t + 1;
                lent.remove(a);
                pool.release(a);
            }
        });
        assertEquals(0, clashes.get());
        assertTrue(pool.available() <= 64);
    }

    @Test
    void poolWithZeroOrNegativeCapacityNeverRetains() {
        for (int cap : new int[]{0, -1, Integer.MIN_VALUE}) {
            BoundedPool<Object> pool = BoundedPool.of(Object::new, cap);
            Object o = pool.borrow();
            pool.release(o);
            assertEquals(0, pool.available());
            assertEquals(0, pool.capacity());
        }
    }

    // ─── utilitaires ───────────────────────────────────────────────────────

    static int capacity(LongObjectCache<?> c) throws Exception {
        Field f = LongObjectCache.class.getDeclaredField("keys");
        f.setAccessible(true);
        synchronized (lockOf(c)) { return ((long[]) f.get(c)).length; }
    }

    private static Object lockOf(Object c) throws Exception {
        Field f = c.getClass().getDeclaredField("lock");
        f.setAccessible(true);
        return f.get(c);
    }

    @SuppressWarnings("unchecked")
    static Queue<Object> pending(AsyncLeaderboard<?> lb) throws Exception {
        Field f = AsyncLeaderboard.class.getDeclaredField("pending");
        f.setAccessible(true);
        return (Queue<Object>) f.get(lb);
    }

    /** Applique les écritures en attente comme le ferait le thread de rafraîchissement. */
    static void drain(AsyncLeaderboard<?> lb) throws Exception {
        java.lang.reflect.Method tick = AsyncLeaderboard.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(lb);
    }
}
