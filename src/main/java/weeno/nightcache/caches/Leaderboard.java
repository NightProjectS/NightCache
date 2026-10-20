package weeno.nightcache.caches;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import weeno.nightcache.interfaces.Managed;
import weeno.nightcache.objects.CacheStats;
import weeno.nightcache.objects.LeaderboardEntry;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classement {@code K → score (long)} avec lecture top-N triée.
 * <p>
 * Une cellule {@code long[2]} par clé, mutée en place : {@code [score, rang publié]}. Le rang publié porte la
 * génération du snapshot dans ses 32 bits hauts : aucun index secondaire ni {@code Integer} boxé.
 * <p>
 * Le top-N est calculé par <b>sélection partielle</b> (tas-min borné à N, sur tableaux primitifs réutilisés) en
 * {@code O(m log N)}, puis trié en place : seules les {@link LeaderboardEntry} publiées sont allouées. Le snapshot
 * est recalculé après toute écriture (lecture exacte) ou pour un top plus grand, et réutilisé tel quel sinon : sans
 * écriture, {@code top} n'alloue rien (la vue des {@code n} premiers est elle aussi réutilisée). Les rangs hors top
 * sont comptés en {@code O(m)} sur les scores courants ; dans le top, à score égal, le rang est la position publiée.
 * <p>
 * Thread: thread-safe.
 */
public final class Leaderboard<K> implements Managed {

    private static final int DEFAULT_CAP = 1_000;
    private static final long RANK_MASK = 0xFFFF_FFFFL;

    private final String name;
    private final Object lock = new Object();
    private final HashMap<K, long[]> scores = new HashMap<>(256);

    private List<LeaderboardEntry<K>> snapshot = Collections.emptyList();
    private int snapshotCap;
    private int generation;
    private boolean dirty = true;
    private long queries;
    // Dernière vue « n premiers » du snapshot courant, réutilisée tant que ni le snapshot ni n ne changent.
    private @Nullable List<LeaderboardEntry<K>> view;
    private int viewN;

    // Tampons du tas, réutilisés d'un calcul à l'autre (références nettoyées après usage).
    private Object[] heapKeys = new Object[0];
    private long[] heapScores = new long[0];
    private long[][] heapCells = new long[0][];

    /**
     * @param refreshMs conservé pour la compatibilité de l'API 1.x : sans écriture le snapshot reste exact, il n'est
     *                  donc plus recalculé périodiquement (aucun effet sur les résultats).
     */
    public Leaderboard(@NotNull String name, long refreshMs) {
        this.name = name;
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
            long stamp = c[1];
            if (stamp != 0L && (int) (stamp >>> 32) == generation) return (int) (stamp & RANK_MASK);
            long s = c[0];
            int greater = 0;
            for (long[] o : scores.values()) if (o[0] > s) greater++;
            return greater + 1;
        }
    }

    /** @return les {@code n} premiers du snapshot, sans copie (vue immuable). */
    @NotNull
    @Unmodifiable
    public List<LeaderboardEntry<K>> top(int n) {
        synchronized (lock) {
            refreshIfNeeded(n);
            queries++;
            List<LeaderboardEntry<K>> s = snapshot;
            if (n <= 0 || s.isEmpty()) return Collections.emptyList();
            if (n >= s.size()) return s;
            List<LeaderboardEntry<K>> v = view;
            if (v == null || viewN != n) {
                view = v = s.subList(0, n);
                viewN = n;
            }
            return v;
        }
    }

    /** @return génération du snapshot publié : change à chaque recalcul (détection de changement sans copie). */
    public int generation() {
        synchronized (lock) { return generation; }
    }

    public void remove(@NotNull K key) {
        synchronized (lock) {
            if (scores.remove(key) != null) dirty = true;
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
            snapshot = Collections.emptyList();
            snapshotCap = 0;
            view = null;
            generation++;
            dirty = true;
            heapKeys = new Object[0];
            heapScores = new long[0];
            heapCells = new long[0][];
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
        return scores.computeIfAbsent(key, k -> new long[2]);
    }

    /**
     * Sous verrou. Une écriture déclenche toujours un recalcul (les lectures persistent l'état exact, ex. sauvegarde
     * des votes par {@code top(estimatedSize())}) ; sans écriture, le snapshot reste exact et n'est jamais recalculé
     * (l'ancien recalcul périodique, à {@code refreshMs} = 0 dans {@code AsyncLeaderboard}, triait et allouait à chaque
     * lecture pour un résultat identique).
     */
    private void refreshIfNeeded(int wantN) {
        if (!dirty && wantN <= snapshotCap) return;
        int cap = Math.max(wantN, DEFAULT_CAP);
        int limit = Math.min(cap, scores.size());
        ensureHeap(limit);
        Object[] hk = heapKeys;
        long[] hs = heapScores;
        long[][] hc = heapCells;

        int size = 0;
        for (Map.Entry<K, long[]> e : scores.entrySet()) {
            long[] c = e.getValue();
            long v = c[0];
            if (size < limit) {
                hk[size] = e.getKey();
                hs[size] = v;
                hc[size] = c;
                siftUp(hk, hs, hc, size++);
            } else if (limit > 0 && v > hs[0]) {
                hk[0] = e.getKey();
                hs[0] = v;
                hc[0] = c;
                siftDown(hk, hs, hc, 0, size);
            }
        }
        // Tri en place : le minimum part en fin de tableau à chaque passe → ordre décroissant.
        for (int end = size - 1; end > 0; end--) {
            swap(hk, hs, hc, 0, end);
            siftDown(hk, hs, hc, 0, end);
        }

        int gen = generation + 1;
        @SuppressWarnings("unchecked")
        LeaderboardEntry<K>[] out = (LeaderboardEntry<K>[]) new LeaderboardEntry<?>[size];
        long stampBase = (long) gen << 32;
        for (int r = 0; r < size; r++) {
            @SuppressWarnings("unchecked")
            K key = (K) hk[r];
            out[r] = new LeaderboardEntry<>(key, hs[r], r + 1);
            hc[r][1] = stampBase | (r + 1);
            hk[r] = null; // pas de rétention des clés entre deux calculs
            hc[r] = null;
        }
        snapshot = size == 0 ? Collections.emptyList() : Collections.unmodifiableList(Arrays.asList(out));
        view = null;
        snapshotCap = cap;
        generation = gen;
        dirty = false;
    }

    /** Agrandit les tampons si besoin ; les rend s'ils sont devenus très surdimensionnés. */
    private void ensureHeap(int n) {
        int len = heapScores.length;
        if (len >= n && len <= Math.max(64, n << 2)) return;
        heapKeys = new Object[n];
        heapScores = new long[n];
        heapCells = new long[n][];
    }

    private static void siftUp(Object[] k, long[] s, long[][] c, int i) {
        while (i > 0) {
            int p = (i - 1) >>> 1;
            if (s[p] <= s[i]) return;
            swap(k, s, c, p, i);
            i = p;
        }
    }

    private static void siftDown(Object[] k, long[] s, long[][] c, int i, int size) {
        while (true) {
            int l = (i << 1) + 1;
            if (l >= size) return;
            int m = l + 1 < size && s[l + 1] < s[l] ? l + 1 : l;
            if (s[i] <= s[m]) return;
            swap(k, s, c, i, m);
            i = m;
        }
    }

    private static void swap(Object[] k, long[] s, long[][] c, int a, int b) {
        Object tk = k[a]; k[a] = k[b]; k[b] = tk;
        long ts = s[a]; s[a] = s[b]; s[b] = ts;
        long[] tc = c[a]; c[a] = c[b]; c[b] = tc;
    }
}
