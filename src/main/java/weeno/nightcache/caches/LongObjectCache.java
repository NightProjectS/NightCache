package weeno.nightcache.caches;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.interfaces.Managed;
import weeno.nightcache.objects.CacheStats;

import java.util.Arrays;
import java.util.function.LongFunction;

/**
 * Cache {@code long → V} sans autoboxing ni dépendance (clés de chunk / bloc via {@code Keys}).
 * <p>
 * Table de hachage à chaînage par indices (tableaux primitifs) + liste doublement chaînée LRU :
 * get/put/remove en O(1), zéro objet alloué par entrée hormis la valeur. Borne {@code maxSize}
 * (éviction LRU) et TTL optionnel en écriture, purgé paresseusement et par {@link #cleanUp()}.
 * <p>
 * Thread: thread-safe (verrou interne, sections très courtes).
 */
public final class LongObjectCache<V> implements Managed {

    private static final int NONE = -1;

    private final String name;
    private final long maxSize;
    private final long ttlMs;
    private final Object lock = new Object();

    private int[] table;      // tête de chaîne par bucket
    private long[] keys;
    private Object[] values;
    private long[] expires;   // null si pas de TTL
    private int[] chain;      // suivant dans le bucket, ou suivant libre
    private int[] before;     // LRU
    private int[] after;
    private int head = NONE;  // plus ancien
    private int tail = NONE;  // plus récent
    private int free = NONE;
    private int used;         // slots déjà distribués
    private int size;

    private long hits;
    private long misses;
    private long evictions;

    public LongObjectCache(@NotNull String name, long maxSize, long ttlMs) {
        this.name = name;
        this.maxSize = maxSize;
        this.ttlMs = ttlMs;
        allocate(16);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    public V getIfPresent(long key) {
        synchronized (lock) {
            int i = live(key);
            if (i == NONE) { misses++; return null; }
            hits++;
            touch(i);
            return (V) values[i];
        }
    }

    @NotNull
    @SuppressWarnings("unchecked")
    public V get(long key, @NotNull LongFunction<V> loader) {
        synchronized (lock) {
            int i = live(key);
            if (i != NONE) { hits++; touch(i); return (V) values[i]; }
            misses++;
            V v = loader.apply(key);
            insert(key, v);
            return v;
        }
    }

    public void put(long key, @NotNull V value) {
        synchronized (lock) { insert(key, value); }
    }

    public void invalidate(long key) {
        synchronized (lock) {
            int i = find(key);
            if (i != NONE) remove(i);
        }
    }

    public boolean containsKey(long key) {
        synchronized (lock) { return live(key) != NONE; }
    }

    @Override @NotNull @Contract(pure = true)
    public String name() { return name; }

    @Override
    public long estimatedSize() { synchronized (lock) { return size; } }

    @Override
    public void invalidateAll() {
        synchronized (lock) { allocate(16); }
    }

    @Override
    public void cleanUp() {
        if (expires == null) return;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            int i = head;
            while (i != NONE) {
                int next = after[i];
                if (expires[i] <= now) { remove(i); evictions++; }
                i = next;
            }
            shrinkIfSparse();
        }
    }

    @Override @NotNull
    public CacheStats stats() {
        synchronized (lock) { return new CacheStats(name, size, hits, misses, evictions); }
    }

    // ─── interne (sous verrou) ──────────────────────────────────────────────

    private void allocate(int capacity) {
        table = new int[Integer.highestOneBit(Math.max(16, capacity) - 1) << 1];
        Arrays.fill(table, NONE);
        keys = new long[capacity];
        values = new Object[capacity];
        expires = ttlMs > 0 ? new long[capacity] : null;
        chain = new int[capacity];
        before = new int[capacity];
        after = new int[capacity];
        head = tail = free = NONE;
        used = size = 0;
    }

    private static int mix(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        return (int) (h ^ (h >>> 32));
    }

    private int find(long key) {
        int i = table[mix(key) & (table.length - 1)];
        while (i != NONE && keys[i] != key) i = chain[i];
        return i;
    }

    /** @return l'indice si présent et non expiré (l'entrée expirée est retirée au passage). */
    private int live(long key) {
        int i = find(key);
        if (i != NONE && expires != null && expires[i] <= System.currentTimeMillis()) {
            remove(i);
            evictions++;
            return NONE;
        }
        return i;
    }

    private void insert(long key, Object value) {
        int i = find(key);
        if (i != NONE) {
            values[i] = value;
            if (expires != null) expires[i] = System.currentTimeMillis() + ttlMs;
            touch(i);
            return;
        }
        if (maxSize > 0 && size >= maxSize && head != NONE) { remove(head); evictions++; }
        if (free == NONE && used == keys.length) grow();
        if (free != NONE) { i = free; free = chain[i]; } else { i = used++; }
        keys[i] = key;
        values[i] = value;
        if (expires != null) expires[i] = System.currentTimeMillis() + ttlMs;
        int b = mix(key) & (table.length - 1);
        chain[i] = table[b];
        table[b] = i;
        before[i] = tail;
        after[i] = NONE;
        if (tail != NONE) after[tail] = i; else head = i;
        tail = i;
        size++;
    }

    private void remove(int i) {
        int b = mix(keys[i]) & (table.length - 1);
        if (table[b] == i) table[b] = chain[i];
        else {
            int p = table[b];
            while (chain[p] != i) p = chain[p];
            chain[p] = chain[i];
        }
        if (before[i] != NONE) after[before[i]] = after[i]; else head = after[i];
        if (after[i] != NONE) before[after[i]] = before[i]; else tail = before[i];
        values[i] = null; // pas de rétention de la valeur
        chain[i] = free;
        free = i;
        size--;
    }

    private void touch(int i) {
        if (i == tail) return;
        if (before[i] != NONE) after[before[i]] = after[i]; else head = after[i];
        before[after[i]] = before[i];
        before[i] = tail;
        after[i] = NONE;
        after[tail] = i;
        tail = i;
    }

    private void grow() {
        rebuild(keys.length << 1);
    }

    /** Rend la mémoire quand le cache s'est vidé (évite de garder un pic de taille indéfiniment). */
    private void shrinkIfSparse() {
        if (keys.length > 64 && size < keys.length >>> 3) rebuild(Math.max(16, size << 1));
    }

    /**
     * Recopie les entrées vivantes, dans l'ordre LRU, directement dans des tableaux neufs de {@code capacity}
     * cases (indices compactés 0..n-1) : aucun tableau temporaire ni recherche de hachage par entrée.
     */
    private void rebuild(int capacity) {
        long[] oldKeys = keys;
        Object[] oldValues = values;
        long[] oldExpires = expires;
        int[] oldAfter = after;
        int from = head;
        allocate(Math.max(capacity, size));
        int mask = table.length - 1;
        int n = 0;
        for (int i = from; i != NONE; i = oldAfter[i]) {
            long k = oldKeys[i];
            keys[n] = k;
            values[n] = oldValues[i];
            if (oldExpires != null) expires[n] = oldExpires[i];
            int b = mix(k) & mask;
            chain[n] = table[b];
            table[b] = n;
            before[n] = n - 1; // NONE (-1) pour le premier
            after[n] = NONE;
            if (n > 0) after[n - 1] = n;
            n++;
        }
        if (n > 0) {
            head = 0;
            tail = n - 1;
        }
        used = size = n;
    }
}
