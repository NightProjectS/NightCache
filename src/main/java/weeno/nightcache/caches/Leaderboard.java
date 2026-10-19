package weeno.nightcache.caches;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import weeno.nightcache.interfaces.Managed;
import weeno.nightcache.objects.CacheStats;
import weeno.nightcache.objects.LeaderboardEntry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Classement {@code K → score (long)} avec lecture top-N triée.
 * <p>
 * Score par clé dans une cellule {@code long[1]} mutée en place (pas de boxing à chaque add).
 * Le top-N est calculé par <b>sélection partielle</b> (min-heap borné à N) en {@code O(m log N)},
 * snapshot mis en cache et throttlé ({@code refreshMs}). Les rangs hors top sont comptés en {@code O(m)}.
 * <p>
 * Thread: thread-safe.
 */
public final class Leaderboard<K> implements Managed {

    private static final int DEFAULT_CAP = 1_000;

    private final String name;
    private final long refreshMs;
    private final Object lock = new Object();
    private final HashMap<K, long[]> scores = new HashMap<>(256);
    private final HashMap<K, Integer> topRanks = new HashMap<>(256);

    private List<LeaderboardEntry<K>> snapshot = Collections.emptyList();
    private int snapshotCap;
    private long lastRefresh;
    private boolean dirty = true;
    private long queries;

    public Leaderboard(@NotNull String name, long refreshMs) {
        this.name = name;
        this.refreshMs = refreshMs;
    }

    public void set(@NotNull K key, long score) {
        synchronized (lock) { cell(key)[0] = score; dirty = true; }
    }

    public long add(@NotNull K key, long delta) {
        synchronized (lock) {
            long[] c = cell(key);
            c[0] += delta;
            dirty = true;
            return c[0];
        }
    }

    public long score(@NotNull K key) {
        synchronized (lock) {
            long[] c = scores.get(key);
            return c == null ? 0L : c[0];
        }
    }

    /** @return rang 1-based, ou {@code -1} si la clé est inconnue. */
    public int rank(@NotNull K key) {
        synchronized (lock) {
            long[] c = scores.get(key);
            if (c == null) return -1;
            refreshIfNeeded(snapshotCap == 0 ? DEFAULT_CAP : snapshotCap);
            Integer r = topRanks.get(key);
            if (r != null) return r;
            long s = c[0];
            int greater = 0;
            for (long[] o : scores.values()) if (o[0] > s) greater++;
            return greater + 1;
        }
    }

    @NotNull
    @Unmodifiable
    public List<LeaderboardEntry<K>> top(int n) {
        synchronized (lock) {
            refreshIfNeeded(n);
            queries++;
            int size = Math.min(n, snapshot.size());
            if (size <= 0) return Collections.emptyList();
            return Collections.unmodifiableList(new ArrayList<>(snapshot.subList(0, size)));
        }
    }

    public void remove(@NotNull K key) {
        synchronized (lock) {
            scores.remove(key);
            topRanks.remove(key);
            dirty = true;
        }
    }

    @Override @NotNull @Contract(pure = true)
    public String name() { return name; }

    @Override
    public long estimatedSize() { synchronized (lock) { return scores.size(); } }

    @Override
    public void invalidateAll() {
        synchronized (lock) {
            scores.clear();
            topRanks.clear();
            snapshot = Collections.emptyList();
            snapshotCap = 0;
            dirty = true;
        }
    }

    @Override
    public void cleanUp() {
        synchronized (lock) { refreshIfNeeded(snapshotCap == 0 ? DEFAULT_CAP : snapshotCap); }
    }

    @Override @NotNull
    public CacheStats stats() {
        synchronized (lock) { return new CacheStats(name, scores.size(), queries, 0, 0); }
    }

    private long[] cell(@NotNull K key) {
        return scores.computeIfAbsent(key, k -> new long[1]);
    }

    private void refreshIfNeeded(int wantN) {
        long now = System.currentTimeMillis();
        if (!dirty && wantN <= snapshotCap && now - lastRefresh < refreshMs) return;
        int cap = Math.max(wantN, DEFAULT_CAP);
        PriorityQueue<LeaderboardEntry<K>> heap = new PriorityQueue<>(
                Math.min(cap, scores.size()) + 1, Comparator.comparingLong(LeaderboardEntry::score));
        for (Map.Entry<K, long[]> e : scores.entrySet()) {
            long v = e.getValue()[0];
            if (heap.size() < cap) {
                heap.offer(new LeaderboardEntry<>(e.getKey(), v, 0));
            } else if (v > heap.peek().score()) {
                heap.poll();
                heap.offer(new LeaderboardEntry<>(e.getKey(), v, 0));
            }
        }
        int size = heap.size();
        List<LeaderboardEntry<K>> desc = new ArrayList<>(size);
        for (int i = 0; i < size; i++) desc.add(null);
        for (int i = size - 1; i >= 0; i--) desc.set(i, heap.poll());
        topRanks.clear();
        List<LeaderboardEntry<K>> out = new ArrayList<>(size);
        for (int r = 0; r < size; r++) {
            LeaderboardEntry<K> e = new LeaderboardEntry<>(desc.get(r).key(), desc.get(r).score(), r + 1);
            out.add(e);
            topRanks.put(e.key(), r + 1);
        }
        snapshot = out;
        snapshotCap = cap;
        lastRefresh = now;
        dirty = false;
    }
}
