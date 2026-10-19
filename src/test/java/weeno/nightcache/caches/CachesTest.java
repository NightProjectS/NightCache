package weeno.nightcache.caches;

import org.junit.jupiter.api.Test;
import weeno.nightcache.objects.LeaderboardEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachesTest {

    // ─── LongObjectCache ───────────────────────────────────────────────────

    @Test
    void longCacheEvictsLeastRecentlyUsed() {
        LongObjectCache<String> c = new LongObjectCache<>("t", 3, 0);
        c.put(1, "a");
        c.put(2, "b");
        c.put(3, "c");
        assertEquals("a", c.getIfPresent(1)); // 1 devient le plus récent
        c.put(4, "d");                       // évince 2
        assertNull(c.getIfPresent(2));
        assertEquals("a", c.getIfPresent(1));
        assertEquals("c", c.getIfPresent(3));
        assertEquals("d", c.getIfPresent(4));
        assertEquals(3, c.estimatedSize());
    }

    @Test
    void longCacheKeepsEntriesAndLruOrderAcrossGrowAndShrink() {
        LongObjectCache<Long> c = new LongObjectCache<>("t", 0, 0);
        for (long k = 0; k < 5_000; k++) c.put(k * 7919, k); // plusieurs agrandissements
        for (long k = 0; k < 5_000; k++) assertEquals(k, c.getIfPresent(k * 7919));
        for (long k = 0; k < 4_990; k++) c.invalidate(k * 7919);
        c.cleanUp(); // sans TTL : pas de purge, mais pas de perte non plus
        assertEquals(10, c.estimatedSize());
        for (long k = 4_990; k < 5_000; k++) assertEquals(k, c.getIfPresent(k * 7919));
    }

    @Test
    void longCacheShrinksAfterExpiryWithoutLosingLiveEntries() throws InterruptedException {
        LongObjectCache<String> c = new LongObjectCache<>("t", 0, 40);
        for (long k = 0; k < 1_000; k++) c.put(k, "v" + k);
        Thread.sleep(60);
        c.put(-1, "frais");
        c.cleanUp(); // purge + compactage (rebuild)
        assertEquals(1, c.estimatedSize());
        assertEquals("frais", c.getIfPresent(-1));
        for (long k = 0; k < 100; k++) c.put(k, "n" + k); // la table reconstruite reste utilisable
        assertEquals("n42", c.getIfPresent(42));
        assertEquals(101, c.estimatedSize());
    }

    @Test
    void longCacheLoaderRunsOnlyOnMiss() {
        LongObjectCache<String> c = new LongObjectCache<>("t", 10, 0);
        AtomicInteger calls = new AtomicInteger();
        assertEquals("x5", c.get(5, k -> { calls.incrementAndGet(); return "x" + k; }));
        assertEquals("x5", c.get(5, k -> { calls.incrementAndGet(); return "autre"; }));
        assertEquals(1, calls.get());
    }

    // ─── ObjectLongCache ───────────────────────────────────────────────────

    @Test
    void objectLongCacheWithoutTtlCountsAndDefaults() {
        ObjectLongCache<String> c = new ObjectLongCache<>("t", 0, 0, -1);
        assertEquals(-1, c.getLong("absent"));
        assertEquals(4, c.addTo("k", 5)); // défaut -1 + 5
        c.put("k", 10);
        assertEquals(10, c.getLong("k"));
        c.cleanUp();
        assertTrue(c.containsKey("k"));
    }

    @Test
    void objectLongCacheExpiresWithTtl() throws InterruptedException {
        ObjectLongCache<String> c = new ObjectLongCache<>("t", 0, 30, 0);
        c.put("k", 7);
        assertEquals(7, c.getLong("k"));
        Thread.sleep(50);
        assertFalse(c.containsKey("k"));
        assertEquals(0, c.getLong("k"));
    }

    // ─── Leaderboard ───────────────────────────────────────────────────────

    @Test
    void leaderboardTopIsSortedAndRanked() {
        Leaderboard<String> lb = new Leaderboard<>("t", 0);
        for (int i = 0; i < 50; i++) lb.set("p" + i, i * 3L % 50);
        List<LeaderboardEntry<String>> top = lb.top(10);
        assertEquals(10, top.size());
        for (int i = 0; i < top.size(); i++) {
            assertEquals(i + 1, top.get(i).rank());
            if (i > 0) assertTrue(top.get(i - 1).score() >= top.get(i).score());
            assertEquals(i + 1, lb.rank(top.get(i).key()));
        }
        assertThrows(UnsupportedOperationException.class, () -> top.set(0, top.get(1)));
    }

    @Test
    void leaderboardMatchesAFullSortOnLargeInput() {
        Leaderboard<Integer> lb = new Leaderboard<>("t", 0);
        List<Long> all = new ArrayList<>();
        java.util.Random r = new java.util.Random(42);
        for (int i = 0; i < 5_000; i++) {
            long s = r.nextInt(1_000_000);
            lb.set(i, s);
            all.add(s);
        }
        all.sort((a, b) -> Long.compare(b, a));
        List<LeaderboardEntry<Integer>> top = lb.top(1_000);
        assertEquals(1_000, top.size());
        for (int i = 0; i < 1_000; i++) assertEquals(all.get(i), top.get(i).score());
    }

    @Test
    void leaderboardRanksOutsideTopByCounting() {
        Leaderboard<String> lb = new Leaderboard<>("t", 0);
        for (int i = 0; i < 1_500; i++) lb.set("p" + i, i);
        lb.top(10); // snapshot de 1000 (minimum)
        assertEquals(1_500, lb.rank("p0"));     // hors top : comptage
        assertEquals(1, lb.rank("p1499"));      // dans le top : rang estampillé
        assertEquals(-1, lb.rank("inconnu"));
    }

    @Test
    void leaderboardReadsAreExactRightAfterWrites() {
        // Garantie dont dépend la sauvegarde des votes (top(estimatedSize()) juste après un vote).
        Leaderboard<String> lb = new Leaderboard<>("t", 60_000);
        lb.set("a", 1);
        lb.top(5);
        lb.add("a", 100);
        lb.set("nouveau", 50);
        List<LeaderboardEntry<String>> top = lb.top((int) lb.estimatedSize());
        assertEquals(2, top.size(), "un nouveau votant apparaît immédiatement");
        assertEquals(101, top.get(0).score());
        assertEquals(2, lb.rank("nouveau"));
    }

    @Test
    void leaderboardReusesSnapshotWithoutWrites() {
        Leaderboard<String> lb = new Leaderboard<>("t", 60_000);
        lb.set("a", 1);
        List<LeaderboardEntry<String>> first = lb.top(5);
        int gen = lb.generation();
        assertSame(first, lb.top(5), "aucune écriture : ni recalcul ni allocation");
        assertEquals(gen, lb.generation());
    }

    @Test
    void leaderboardRefreshesWhenBiggerTopIsRequested() {
        Leaderboard<String> lb = new Leaderboard<>("t", 60_000);
        for (int i = 0; i < 1_200; i++) lb.set("p" + i, i);
        assertEquals(1_000, lb.top(1_000).size());
        assertEquals(1_200, lb.top(1_200).size());
    }

    @Test
    void leaderboardInvalidateAndRemove() {
        Leaderboard<String> lb = new Leaderboard<>("t", 0);
        lb.set("a", 5);
        lb.set("b", 3);
        lb.remove("a");
        assertEquals(-1, lb.rank("a"));
        assertEquals("b", lb.top(1).get(0).key());
        int gen = lb.generation();
        lb.invalidateAll();
        assertTrue(lb.top(10).isEmpty());
        assertNotSame(gen, lb.generation());
    }

    // ─── BoundedPool ───────────────────────────────────────────────────────

    @Test
    void poolReusesResetsAndStaysBounded() {
        AtomicInteger created = new AtomicInteger();
        BoundedPool<StringBuilder> pool = BoundedPool.of(() -> { created.incrementAndGet(); return new StringBuilder(); },
                sb -> sb.setLength(0), 2);
        StringBuilder a = pool.borrow();
        a.append("sale");
        pool.release(a);
        StringBuilder again = pool.borrow();
        assertSame(a, again);
        assertEquals(0, again.length());
        pool.release(new StringBuilder());
        pool.release(new StringBuilder());
        pool.release(new StringBuilder()); // au-delà de la capacité : abandonné
        assertEquals(2, pool.available());
        pool.clear();
        assertEquals(0, pool.available());
        assertEquals(1, created.get());
    }

    @Test
    void poolGrowsUpToItsCapacity() {
        BoundedPool<Object> pool = BoundedPool.of(Object::new, 100);
        List<Object> lent = new ArrayList<>();
        for (int i = 0; i < 100; i++) lent.add(pool.borrow());
        lent.forEach(pool::release);
        assertEquals(100, pool.available());
    }
}
