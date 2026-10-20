package weeno.nightcache.caches;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import weeno.nightcache.objects.LeaderboardEntry;
import weeno.nightcache.platform.Platform;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Façade <b>100 % lock-free côté main thread</b> au-dessus d'un {@link Leaderboard}.
 * <p>
 * Les écritures ({@link #add}/{@link #set}) sont enfilées dans une queue MPSC lock-free —
 * le main thread ne prend <b>jamais</b> de verrou. Un unique thread async draine la queue,
 * applique les updates et recalcule le top-N, publié dans des champs {@code volatile}.
 *
 * @param <K> type de la clé classée.
 */
public final class AsyncLeaderboard<K> {

    private static final class Update<K> {
        final K key;
        final long value;
        final boolean set;

        Update(K key, long value, boolean set) {
            this.key = key;
            this.value = value;
            this.set = set;
        }
    }

    private final Leaderboard<K> delegate;
    private final int topN;
    private final long periodTicks;
    private final ConcurrentLinkedQueue<Update<K>> pending = new ConcurrentLinkedQueue<>();

    private volatile List<LeaderboardEntry<K>> top = Collections.emptyList();
    private volatile Map<K, Integer> ranks = Collections.emptyMap();
    private volatile Runnable task;
    /** Génération du dernier top publié (thread async uniquement). */
    private volatile int publishedGeneration = -1;
    /** Arrêté par {@link #stop()} : les écritures sont ignorées (plus personne ne viderait la file). */
    private volatile boolean stopped;
    /** Dernière vue « n premiers » du top publié (lecture sans allocation tant que le top ne change pas). */
    private volatile @Nullable TopView<K> view;

    private record TopView<K>(List<LeaderboardEntry<K>> source, int n, List<LeaderboardEntry<K>> list) {}

    public AsyncLeaderboard(@NotNull String name, int topN, long refreshMs, long periodTicks) {
        this.delegate = new Leaderboard<>(name, refreshMs);
        this.topN = topN;
        this.periodTicks = periodTicks;
    }

    /** Démarre (ou redémarre après {@link #stop()}) le rafraîchissement async périodique. Idempotent. */
    public synchronized void start(@NotNull Plugin plugin) {
        if (task != null) return;
        stopped = false;
        long periodMs = Math.min(Math.max(1L, periodTicks), Long.MAX_VALUE / 50L) * 50L; // sans dépassement
        task = Platform.asyncTimer("leaderboard", this::tick, periodMs);
    }

    /** Stoppe le rafraîchissement (shutdown) ; les écritures suivantes sont ignorées jusqu'à {@link #start}. */
    public synchronized void stop() {
        stopped = true;
        Runnable t = task;
        task = null;
        if (t != null) t.run();
        pending.clear();
        delegate.invalidateAll();
        top = Collections.emptyList();
        ranks = Collections.emptyMap();
        view = null;
        publishedGeneration = -1;
    }

    /** Ajoute {@code delta} au score — <b>lock-free</b>, appelable depuis le main thread. */
    public void add(@NotNull K key, long delta) {
        if (stopped) return;
        pending.offer(new Update<>(key, delta, false));
    }

    /** Fixe le score absolu — <b>lock-free</b>, appelable depuis le main thread. */
    public void set(@NotNull K key, long score) {
        if (stopped) return;
        pending.offer(new Update<>(key, score, true));
    }

    /** @return le dernier top-N publié — lecture {@code volatile} sans lock ni copie. */
    @NotNull
    @Unmodifiable
    public List<LeaderboardEntry<K>> getTop() {
        return top;
    }

    /**
     * @return les {@code n} premiers du dernier snapshot — sans lock, et sans allocation tant que le top publié et
     * {@code n} ne changent pas.
     */
    @NotNull
    @Unmodifiable
    public List<LeaderboardEntry<K>> getTop(int n) {
        List<LeaderboardEntry<K>> t = top;
        if (n >= t.size()) return t;
        TopView<K> v = view;
        if (v != null && v.source() == t && v.n() == n) return v.list();
        List<LeaderboardEntry<K>> sub = t.subList(0, Math.max(0, n));
        view = new TopView<>(t, n, sub);
        return sub;
    }

    /** @return rang (1 = meilleur) si dans le top-N publié, sinon {@code -1}. Sans lock. */
    public int getRank(@NotNull K key) {
        Integer r = ranks.get(key);
        return r == null ? -1 : r;
    }

    /** @return nombre d'entrées classées (snapshot). */
    @Contract(pure = true)
    public long estimatedSize() {
        return delegate.estimatedSize();
    }

    private void tick() {
        Update<K> u;
        while ((u = pending.poll()) != null) {
            if (u.set) delegate.set(u.key, u.value);
            else delegate.add(u.key, u.value);
        }
        List<LeaderboardEntry<K>> snap = delegate.top(topN);
        int gen = delegate.generation();
        if (gen == publishedGeneration) return; // top inchangé : rien à republier ni à allouer
        Map<K, Integer> r = new HashMap<>(Math.max(16, snap.size() * 2));
        for (int i = 0, n = snap.size(); i < n; i++) {
            LeaderboardEntry<K> e = snap.get(i);
            r.put(e.key(), e.rank());
        }
        this.top = snap;
        this.ranks = r;
        publishedGeneration = gen;
    }

    /** Vide le classement (scores, top publié, rangs, mises à jour en attente). */
    public void invalidateAll() {
        pending.clear();
        delegate.invalidateAll();
        top = Collections.emptyList();
        ranks = Collections.emptyMap();
        view = null;
        publishedGeneration = -1;
    }
}
