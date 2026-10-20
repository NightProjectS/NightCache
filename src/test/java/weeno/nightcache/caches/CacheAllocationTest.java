package weeno.nightcache.caches;

import org.junit.jupiter.api.Test;
import weeno.nightcache.testsupport.Alloc;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chemins chauds sans allocation après échauffement : octets alloués par le thread mesurés exactement sur 100 000
 * appels. La tolérance (quelques centaines d'octets au total, soit &lt; 0,01 octet par appel) absorbe une éventuelle
 * allocation unique de la JVM, jamais une allocation par appel.
 */
class CacheAllocationTest {

    private static final int WARMUP = 50_000;
    private static final int CALLS = 100_000;
    private static final long TOLERANCE = 512L;

    @Test
    void longObjectCacheGetAndPutOnExistingKeyAllocateNothing() {
        LongObjectCache<String> c = new LongObjectCache<>("alloc", 10_000, 0);
        String value = "v";
        for (long k = 0; k < 1_000; k++) c.put(k, value);
        long[] i = {0};
        assertZero("LongObjectCache.getIfPresent", () -> Alloc.sink = c.getIfPresent(i[0]++ % 1_000));
        assertZero("LongObjectCache.put (clé existante)", () -> c.put(i[0]++ % 1_000, value));
    }

    @Test
    void longObjectCacheWithTtlAllocatesNothingOnExistingKey() {
        LongObjectCache<String> c = new LongObjectCache<>("alloc", 0, 3_600_000);
        String value = "v";
        for (long k = 0; k < 100; k++) c.put(k, value);
        long[] i = {0};
        assertZero("LongObjectCache TTL get/put", () -> {
            long k = i[0]++ % 100;
            c.put(k, value);
            Alloc.sink = c.getIfPresent(k);
        });
    }

    @Test
    void objectLongCacheGetLongAndAddToOnExistingKeyAllocateNothing() {
        ObjectLongCache<String> c = new ObjectLongCache<>("alloc", 0, 0, 0);
        String[] keys = new String[64];
        for (int k = 0; k < keys.length; k++) {
            keys[k] = "joueur-" + k;
            c.put(keys[k], k);
        }
        int[] i = {0};
        long[] acc = {0};
        assertZero("ObjectLongCache.getLong", () -> acc[0] += c.getLong(keys[i[0]++ & 63]));
        assertZero("ObjectLongCache.addTo", () -> acc[0] += c.addTo(keys[i[0]++ & 63], 1));
        ObjectLongCache<String> ttl = new ObjectLongCache<>("alloc-ttl", 1_000, 3_600_000, 0);
        for (String k : keys) ttl.put(k, 1);
        assertZero("ObjectLongCache TTL addTo", () -> acc[0] += ttl.addTo(keys[i[0]++ & 63], 1));
        Alloc.sink = acc[0];
    }

    @Test
    void leaderboardTopWithoutWritesAllocatesNothing() {
        for (long refresh : new long[]{0L, 1_000L, 60_000L}) {
            Leaderboard<String> lb = new Leaderboard<>("alloc", refresh);
            for (int k = 0; k < 2_000; k++) lb.set("p" + k, k * 31L % 997);
            lb.top(10);
            assertZero("Leaderboard.top(10) refresh=" + refresh, () -> Alloc.sink = lb.top(10));
            assertZero("Leaderboard.top(snapshot entier) refresh=" + refresh, () -> Alloc.sink = lb.top(1_000));
            String best = lb.top(1).get(0).key();
            long[] acc = {0};
            assertZero("Leaderboard.score/rank (top)", () -> acc[0] += lb.score(best) + lb.rank(best));
        }
    }

    @Test
    void asyncLeaderboardReadsAllocateNothing() throws Exception {
        AsyncLeaderboard<Integer> lb = new AsyncLeaderboard<>("alloc", 50, 0, 1);
        for (int k = 0; k < 500; k++) lb.set(k, k);
        CachesHardeningTest.drain(lb);
        Integer key = 499;
        assertZero("AsyncLeaderboard.getTop(10)", () -> Alloc.sink = lb.getTop(10));
        assertZero("AsyncLeaderboard.getTop()", () -> Alloc.sink = lb.getTop());
        int[] acc = {0};
        assertZero("AsyncLeaderboard.getRank", () -> acc[0] += lb.getRank(key));
        CachesHardeningTest.drain(lb); // rien en attente : pas de republication
        assertZero("AsyncLeaderboard.getTop(10) après un tick vide", () -> Alloc.sink = lb.getTop(10));
    }

    @Test
    void poolBorrowReleaseAllocatesNothing() {
        BoundedPool<StringBuilder> pool = BoundedPool.of(StringBuilder::new, sb -> sb.setLength(0), 8);
        pool.release(new StringBuilder());
        assertZero("BoundedPool borrow/release", () -> {
            StringBuilder sb = pool.borrow();
            pool.release(sb);
        });
    }

    static void assertZero(String what, Runnable op) {
        long bytes = Alloc.bytes(WARMUP, CALLS, op);
        assertTrue(bytes <= TOLERANCE, what + " : " + bytes + " octets alloués pour " + CALLS + " appels");
    }
}
