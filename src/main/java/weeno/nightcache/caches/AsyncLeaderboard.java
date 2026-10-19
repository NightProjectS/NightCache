package weeno.nightcache.caches;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
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

    public AsyncLeaderboard(@NotNull String name, int topN, long refreshMs, long periodTicks) {
        this.delegate = new Leaderboard<>(name, refreshMs);
        this.topN = topN;
        this.periodTicks = periodTicks;
    }

    /** Démarre le rafraîchissement async périodique. Idempotent. */
    public void start(@NotNull Plugin plugin) {
        if (task != null) return;
        task = Platform.asyncTimer("leaderboard", this::tick, Math.max(1L, periodTicks) * 50L);
    }

    /** Stoppe le thread de rafraîchissement (appelé au shutdown). */
    public void stop() {
        Runnable t = task;
        if (t != null) { t.run(); task = null; }
        pending.clear();
        delegate.invalidateAll();
        top = Collections.emptyList();
        ranks = Collections.emptyMap();
    }

    /** Ajoute {@code delta} au score — <b>lock-free</b>, appelable depuis le main thread. */
    public void add(@NotNull K key, long delta) {
        pending.offer(new Update<>(key, delta, false));
    }

    /** Fixe le score absolu — <b>lock-free</b>, appelable depuis le main thread. */
    public void set(@NotNull K key, long score) {
        pending.offer(new Update<>(key, score, true));
    }

    /** @return le dernier top-N publié — lecture {@code volatile} sans lock ni copie. */
    @NotNull
    @Unmodifiable
    public List<LeaderboardEntry<K>> getTop() {
        return top;
    }

    /** @return les {@code n} premiers du dernier snapshot — sans lock. */
    @NotNull
    @Unmodifiable
    public List<LeaderboardEntry<K>> getTop(int n) {
        List<LeaderboardEntry<K>> t = top;
        return n >= t.size() ? t : t.subList(0, Math.max(0, n));
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
        Map<K, Integer> r = new HashMap<>(Math.max(16, snap.size() * 2));
        for (LeaderboardEntry<K> e : snap) r.put(e.key(), e.rank());
        this.top = snap;
        this.ranks = r;
    }

    /** Vide le classement (scores, top publié, rangs, mises à jour en attente). */
    public void invalidateAll() {
        pending.clear();
        delegate.invalidateAll();
        top = Collections.emptyList();
        ranks = Collections.emptyMap();
    }
}
