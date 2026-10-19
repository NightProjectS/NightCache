package weeno.nightcache.caches;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.interfaces.Managed;
import weeno.nightcache.objects.CacheStats;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cache {@code K → long} (cooldowns, compteurs, scores) : une seule cellule {@code long[2]}
 * (valeur, expiration) par clé, mutée en place — pas de boxing à chaque écriture.
 * Ordre d'accès LRU, borne {@code maxSize}, TTL optionnel en écriture.
 * <p>
 * Thread: thread-safe (verrou interne).
 */
public final class ObjectLongCache<K> implements Managed {

    private final String name;
    private final long maxSize;
    private final long ttlMs;
    private final long defaultValue;
    private final Object lock = new Object();
    private final LinkedHashMap<K, long[]> map;

    private long hits;
    private long misses;
    private long evictions;

    public ObjectLongCache(@NotNull String name, long maxSize, long ttlMs, long defaultValue) {
        this.name = name;
        this.maxSize = maxSize;
        this.ttlMs = ttlMs;
        this.defaultValue = defaultValue;
        this.map = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, long[]> eldest) {
                if (ObjectLongCache.this.maxSize > 0 && size() > ObjectLongCache.this.maxSize) {
                    evictions++;
                    return true;
                }
                return false;
            }
        };
    }

    public long getLong(@NotNull K key) {
        synchronized (lock) {
            long[] cell = live(key);
            if (cell == null) { misses++; return defaultValue; }
            hits++;
            return cell[0];
        }
    }

    public void put(@NotNull K key, long value) {
        synchronized (lock) { write(key, value); }
    }

    public long addTo(@NotNull K key, long delta) {
        synchronized (lock) {
            long[] cell = live(key);
            long next = (cell == null ? defaultValue : cell[0]) + delta;
            write(key, next);
            return next;
        }
    }

    public void invalidate(@NotNull K key) {
        synchronized (lock) { map.remove(key); }
    }

    public boolean containsKey(@NotNull K key) {
        synchronized (lock) { return live(key) != null; }
    }

    @Override @NotNull @Contract(pure = true)
    public String name() { return name; }

    @Override
    public long estimatedSize() { synchronized (lock) { return map.size(); } }

    @Override
    public void invalidateAll() {
        synchronized (lock) { map.clear(); }
    }

    @Override
    public void cleanUp() {
        if (ttlMs <= 0) return;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            for (Iterator<long[]> it = map.values().iterator(); it.hasNext(); ) {
                if (it.next()[1] <= now) { it.remove(); evictions++; }
            }
        }
    }

    @Override @NotNull
    public CacheStats stats() {
        synchronized (lock) { return new CacheStats(name, map.size(), hits, misses, evictions); }
    }

    private long[] live(@NotNull K key) {
        long[] cell = map.get(key);
        if (cell != null && ttlMs > 0 && cell[1] <= System.currentTimeMillis()) {
            map.remove(key);
            evictions++;
            return null;
        }
        return cell;
    }

    private void write(@NotNull K key, long value) {
        long[] cell = map.get(key);
        if (cell == null) {
            cell = new long[2];
            map.put(key, cell);
        }
        cell[0] = value;
        cell[1] = ttlMs > 0 ? System.currentTimeMillis() + ttlMs : Long.MAX_VALUE;
    }
}
