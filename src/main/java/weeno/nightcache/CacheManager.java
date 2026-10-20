package weeno.nightcache;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import weeno.nightcache.caches.AsyncLeaderboard;
import weeno.nightcache.caches.BoundedPool;
import weeno.nightcache.caches.CaffeineCache;
import weeno.nightcache.caches.Leaderboard;
import weeno.nightcache.caches.LongObjectCache;
import weeno.nightcache.caches.ObjectLongCache;
import weeno.nightcache.interfaces.Managed;
import weeno.nightcache.interfaces.ManagedCache;
import weeno.nightcache.interfaces.ObjectPool;
import weeno.nightcache.objects.CacheSpec;
import weeno.nightcache.objects.CacheStats;
import weeno.nightcache.platform.Platform;
import weeno.nightcache.utils.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Registre central et statique de tous les caches et pools du plugin.
 * <p>
 * {@link #init(Plugin)} dans {@code onEnable}, {@link #shutdown()} dans {@code onDisable}.
 * Un seul janitor async purge les TTL ; au shutdown tout est vidé — zéro mem leak.
 */
public final class CacheManager {

    private static final long JANITOR_PERIOD_MS = 30_000L;

    private static final ConcurrentHashMap<String, Managed> CACHES = new ConcurrentHashMap<>(32);
    private static final ConcurrentHashMap<String, ObjectPool<?>> POOLS = new ConcurrentHashMap<>(16);
    private static final ConcurrentHashMap<String, AsyncLeaderboard<?>> ASYNC_LB = new ConcurrentHashMap<>(8);
    private static volatile Runnable janitor;
    private static volatile Plugin plugin;
    private static volatile boolean shuttingDown;

    private CacheManager() {}

    /**
     * Démarre le janitor async et les leaderboards async créés avant l'init (sans plugin connu, ils n'étaient
     * jamais rafraîchis). Idempotent.
     */
    public static synchronized void init(@NotNull Plugin pl) {
        shuttingDown = false;
        plugin = pl;
        for (AsyncLeaderboard<?> lb : ASYNC_LB.values()) lb.start(pl);
        if (janitor != null) return;
        // thread démon de la lib (jamais le pool async Bukkit, absent sur Folia)
        janitor = Platform.asyncTimer("janitor", CacheManager::janitorTick, JANITOR_PERIOD_MS);
    }

    /** @return {@code true} pendant/après {@link #shutdown()}. */
    public static boolean isShuttingDown() { return shuttingDown; }

    /** @return statistiques de tous les pools. */
    @NotNull
    @Unmodifiable
    public static List<weeno.nightcache.objects.PoolStats> poolStats() {
        List<weeno.nightcache.objects.PoolStats> out = new ArrayList<>(POOLS.size());
        POOLS.forEach((name, pool) -> out.add(weeno.nightcache.objects.PoolStats.snapshot(name, pool)));
        return Collections.unmodifiableList(out);
    }

    /** @return le plugin hôte, ou {@code null} hors de init/shutdown. */
    @Nullable
    public static Plugin plugin() {
        return plugin;
    }

    /** Cache objet→objet (Caffeine W-TinyLFU), créé/récupéré par son nom. */
    @NotNull
    @SuppressWarnings("unchecked")
    public static <K, V> ManagedCache<K, V> cache(@NotNull CacheSpec spec) {
        return (ManagedCache<K, V>) CACHES.computeIfAbsent(spec.name(), k -> new CaffeineCache<>(spec));
    }

    /** Cache {@code long → V} sans autoboxing (tableaux primitifs). */
    @NotNull
    @SuppressWarnings("unchecked")
    public static <V> LongObjectCache<V> longCache(@NotNull String name, long maxSize, long ttlMs) {
        return (LongObjectCache<V>) CACHES.computeIfAbsent(name, k -> new LongObjectCache<V>(name, maxSize, ttlMs));
    }

    /** Cache {@code K → long} sans autoboxing (cooldowns, compteurs, scores). */
    @NotNull
    @SuppressWarnings("unchecked")
    public static <K> ObjectLongCache<K> objectLongCache(@NotNull String name, long maxSize, long ttlMs, long defaultValue) {
        return (ObjectLongCache<K>) CACHES.computeIfAbsent(name, k -> new ObjectLongCache<K>(name, maxSize, ttlMs, defaultValue));
    }

    /** Classement {@code K → score} avec lecture top-N triée, recalculée seulement après une écriture. */
    @NotNull
    @SuppressWarnings("unchecked")
    public static <K> Leaderboard<K> leaderboard(@NotNull String name, long refreshMs) {
        return (Leaderboard<K>) CACHES.computeIfAbsent(name, k -> new Leaderboard<K>(name, refreshMs));
    }

    /** Raccourci : {@link #leaderboard(String, long)} avec la valeur historique de 1 s. */
    @NotNull
    public static <K> Leaderboard<K> leaderboard(@NotNull String name) {
        return leaderboard(name, 1000L);
    }

    /**
     * Leaderboard <b>lock-free côté main thread</b> : écritures enfilées sans verrou, tri
     * et publication du top-N sur un thread async. Le tri sort du budget de tick.
     *
     * @param name        identifiant unique.
     * @param topN        taille du top maintenu et publié.
     * @param periodTicks intervalle de rafraîchissement async (ex. {@code 60L} = 3 s).
     * @param <K>         type de la clé classée.
     */
    @NotNull
    @SuppressWarnings("unchecked")
    public static <K> AsyncLeaderboard<K> asyncLeaderboard(@NotNull String name, int topN, long periodTicks) {
        return (AsyncLeaderboard<K>) ASYNC_LB.computeIfAbsent(name, k -> {
            AsyncLeaderboard<K> lb = new AsyncLeaderboard<>(name, topN, 0L, periodTicks);
            Plugin p = plugin;
            if (p != null) lb.start(p);
            return lb;
        });
    }

    /**
     * Enregistre un cache spécialisé déjà construit (ex. cache NBT write-back des items) pour
     * qu'il profite du janitor, des stats et du diag. Remplace une entrée du même nom.
     */
    @NotNull
    public static <T extends Managed> T register(@NotNull T cache) {
        CACHES.put(cache.name(), cache);
        return cache;
    }

    /** @return cache enregistré par son nom (tout type), ou {@code null}. */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T extends Managed> T cache(@NotNull String name) {
        return (T) CACHES.get(name);
    }

    /** Pool d'objets réutilisables borné, avec reset personnalisé. */
    @NotNull
    @SuppressWarnings("unchecked")
    public static <T> ObjectPool<T> pool(@NotNull String name, @NotNull Supplier<T> factory,
                                         @NotNull Consumer<T> reset, int maxCapacity) {
        return (ObjectPool<T>) POOLS.computeIfAbsent(name, k -> BoundedPool.of(factory, reset, maxCapacity));
    }

    /** Raccourci : pool sans reset. */
    @NotNull
    public static <T> ObjectPool<T> pool(@NotNull String name, @NotNull Supplier<T> factory, int maxCapacity) {
        return pool(name, factory, t -> {}, maxCapacity);
    }

    /** @return pool existant par son nom, ou {@code null}. */
    @Nullable
    @SuppressWarnings("unchecked")
    public static <T> ObjectPool<T> pool(@NotNull String name) {
        return (ObjectPool<T>) POOLS.get(name);
    }

    /** Vide un cache par son nom. No-op si inconnu. */
    public static void invalidate(@NotNull String name) {
        Managed c = CACHES.get(name);
        if (c != null) c.invalidateAll();
    }

    /** Vide tous les caches enregistrés. */
    public static void invalidateAll() {
        CACHES.values().forEach(Managed::invalidateAll);
    }

    /** @return nombre de caches enregistrés. */
    public static int cacheCount() {
        return CACHES.size();
    }

    /** @return nombre de pools enregistrés. */
    public static int poolCount() {
        return POOLS.size();
    }

    /** @return nombre de leaderboards async actifs. */
    public static int asyncLeaderboardCount() {
        return ASYNC_LB.size();
    }

    /** @return snapshot immutable des stats de tous les caches. */
    @NotNull
    @Unmodifiable
    public static List<CacheStats> stats() {
        List<CacheStats> out = new ArrayList<>(CACHES.size());
        for (Managed c : CACHES.values()) {
            try {
                out.add(c.stats());
            } catch (RuntimeException e) {
                Log.severe("statistiques du cache '" + c.name() + "'", e);
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Stoppe le janitor, les leaderboards async, vide et désenregistre tous les caches et pools. Une erreur d'un
     * cache (cache spécialisé enregistré par le plugin) est journalisée sans empêcher de vider les autres : les
     * registres sont toujours vides en sortie.
     */
    public static synchronized void shutdown() {
        shuttingDown = true;
        Runnable j = janitor;
        janitor = null;
        if (j != null) j.run();
        for (AsyncLeaderboard<?> lb : ASYNC_LB.values()) {
            try {
                lb.stop();
            } catch (RuntimeException e) {
                Log.severe("arrêt d'un leaderboard async", e);
            }
        }
        ASYNC_LB.clear();
        for (Managed c : CACHES.values()) {
            try {
                c.invalidateAll();
                c.cleanUp();
            } catch (RuntimeException e) {
                Log.severe("vidage du cache '" + c.name() + "'", e);
            }
        }
        CACHES.clear();
        for (ObjectPool<?> p : POOLS.values()) {
            try {
                p.clear();
            } catch (RuntimeException e) {
                Log.severe("vidage d'un pool", e);
            }
        }
        POOLS.clear();
        plugin = null;
    }

    private static void janitorTick() {
        for (Managed c : CACHES.values()) {
            try {
                c.cleanUp();
            } catch (RuntimeException e) {
                Log.severe("purge du cache '" + c.name() + "'", e);
            }
        }
    }
}
