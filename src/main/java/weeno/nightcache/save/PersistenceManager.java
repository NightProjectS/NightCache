package weeno.nightcache.save;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.database.SqliteConnections;
import weeno.nightcache.database.SqliteStore;
import weeno.nightcache.interfaces.ManagedCache;
import weeno.nightcache.interfaces.PersistentStore;
import weeno.nightcache.interfaces.Serializer;
import weeno.nightcache.platform.Platform;
import weeno.nightcache.utils.KeyCodec;
import weeno.nightcache.utils.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * Orchestrateur de la persistance disque : {@code <serveur>/Data/<NomDuPlugin>/<store>.db} (SQLite).
 * <ul>
 *   <li><b>Écrivain unique</b> : un seul thread I/O par plugin, créé paresseusement au premier besoin
 *       (un plugin sans store ne coûte aucun thread). Toute opération SQLite y est exécutée.</li>
 *   <li>File d'écritures runtime bornée ({@value #MAX_QUEUE}) : une surcharge est refusée et
 *       journalisée, jamais une croissance mémoire illimitée.</li>
 *   <li>Données joueur : {@link PlayerBinding} (lots sur le main thread, I/O sur le writer).</li>
 *   <li>Arrêt : {@link #beginShutdown()} coupe les entrées, {@link #shutdown()} écrit les derniers
 *       snapshots puis ferme les stores <b>sur le writer</b>, délai total borné.</li>
 * </ul>
 * L'état est statique : la lib doit être shadée et relocalisée dans chaque plugin.
 */
public final class PersistenceManager {

    /** Capacité de la file des écritures runtime. */
    public static final int MAX_QUEUE = 4096;
    private static final long BLOCKING_TIMEOUT_MS = 30_000L;
    private static final long SHUTDOWN_TIMEOUT_MS = 10_000L;
    private static final int AUTOSAVE_TICKS = 6000;
    private static final int SWEEP_TICKS = 600;

    private static final ConcurrentHashMap<String, SqliteStore<?, ?>> STORES = new ConcurrentHashMap<>(16);
    private static final List<PlayerBinding<?>> BINDINGS = new CopyOnWriteArrayList<>();
    private static final List<Runnable> AUTOSAVE_HOOKS = new CopyOnWriteArrayList<>();
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static volatile boolean stopping = true;
    private static volatile boolean runtime;
    private static volatile boolean forceClose;
    private static volatile @Nullable ThreadPoolExecutor io;
    private static volatile @Nullable Thread writer;
    private static volatile @Nullable File folder;
    private static volatile @Nullable String owner;
    private static @Nullable Runnable tickTask;
    private static int ticks;

    private PersistenceManager() {}

    // ─── cycle de vie ───────────────────────────────────────────────────────

    /**
     * Prépare {@code Data/<plugin>/} (créé au premier store) et le tick de persistance.
     * Aucun thread n'est créé ici. Idempotent. Thread: main-thread-only, début de {@code onEnable}.
     */
    public static void init(@NotNull Plugin plugin) {
        if (!stopping) return;
        Log.bind(plugin);
        initializeWriter(new File("Data", plugin.getName()), plugin.getLogger());
        tickTask = Platform.syncTimer(plugin, PersistenceManager::tick, 1L, 1L);
    }

    /** Variante sans Bukkit (tests d'intégration). Thread: main-thread-only. */
    @ApiStatus.Internal
    public static void initializeWriter(@NotNull File directory, @NotNull Logger log) {
        ThreadPoolExecutor old = io;
        if (old != null && !old.isTerminated()) throw new IllegalStateException("Ancien writer SQLite encore actif");
        if (Log.ownerOrNull() == null) Log.bindLogger(log);
        STORES.clear();
        WARNED.clear();
        folder = directory;
        owner = directory.getName();
        io = null;
        writer = null;
        forceClose = false;
        runtime = false;
        ticks = 0;
        stopping = false;
    }

    /** Tick serveur : lots des bindings, autosave, purge des états joueur. Thread: main-thread-only. */
    public static void tick() {
        if (stopping) return;
        ticks++;
        if (ticks % AUTOSAVE_TICKS == 0) flushAll();
        if (ticks % SWEEP_TICKS == 0) PlayerStates.sweep();
        // Boucle indexée : pas d'itérateur alloué à chaque tick (la liste n'est modifiée que sur ce thread).
        for (int i = 0; i < BINDINGS.size(); i++) {
            PlayerBinding<?> binding = BINDINGS.get(i);
            try {
                binding.tick(PersistenceManager::submit);
            } catch (RuntimeException e) {
                Log.severe("tick du binding '" + binding.storeName() + "'", e);
            }
        }
    }

    /** Coupe toutes les entrées (avant le teardown des managers). Thread: main-thread-only. */
    public static void beginShutdown() {
        stopping = true;
        Runnable t = tickTask;
        tickTask = null;
        if (t != null) t.run();
    }

    /**
     * Sauvegarde finale + fermeture, à appeler <b>en dernier</b> dans {@code onDisable} (après la
     * sauvegarde synchrone des managers). Snapshots préparés sur le main thread, écrits et stores
     * fermés sur le writer ; délai total borné à 10 s. Thread: main-thread-only.
     */
    public static void shutdown() {
        shutdown(SHUTDOWN_TIMEOUT_MS);
    }

    @ApiStatus.Internal
    public static void shutdown(long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        beginShutdown();
        List<Runnable> finalJobs = new ArrayList<>();
        for (PlayerBinding<?> binding : BINDINGS) {
            try {
                finalJobs.add(binding.finalSave());
            } catch (RuntimeException e) {
                Log.severe("préparation de la sauvegarde finale '" + binding.storeName() + "'", e);
            }
        }
        List<Runnable> hooks = List.copyOf(AUTOSAVE_HOOKS);
        ThreadPoolExecutor ex = io;
        try {
            if (ex == null) {
                // aucun writer n'a jamais tourné : rien d'ouvert, mais les hooks peuvent écrire
                if (!finalJobs.isEmpty() || !hooks.isEmpty()) {
                    ex = executor();
                } else {
                    return;
                }
            }
            ex.execute(() -> {
                try {
                    finalJobs.forEach(PersistenceManager::safe);
                    hooks.forEach(PersistenceManager::safe);
                } finally {
                    closeStores();
                }
            });
            ex.shutdown();
            long remaining = Math.max(0L, deadline - System.nanoTime());
            if (!ex.awaitTermination(remaining, TimeUnit.NANOSECONDS)) {
                forceClose = true;
                ex.shutdownNow();
                Log.warning("sauvegarde finale interrompue après " + timeoutMillis + " ms : des données peuvent manquer");
            }
        } catch (InterruptedException e) {
            forceClose = true;
            if (ex != null) ex.shutdownNow();
            Thread.currentThread().interrupt();
            Log.severe("arrêt de la persistance interrompu", e);
        } catch (RejectedExecutionException e) {
            forceClose = true;
            if (ex != null) ex.shutdownNow();
            Log.severe("sauvegarde finale refusée", e);
        } finally {
            BINDINGS.clear();
            AUTOSAVE_HOOKS.clear();
            WARNED.clear();
            if (ex == null || ex.isTerminated()) {
                STORES.clear();
                io = null;
                writer = null;
            }
            folder = null;
            owner = null;
            runtime = false;
        }
    }

    // ─── stores ─────────────────────────────────────────────────────────────

    /**
     * Ouvre (ou récupère) le store {@code Data/<plugin>/<name>.db}. Seul point d'entrée autorisé.
     * Idempotent, sans I/O sur l'appelant. Thread: thread-safe.
     *
     * @param name nom snake_case <b>stable</b> (renommer = perdre les données sans migration).
     */
    @NotNull
    @SuppressWarnings("unchecked")
    public static <K, V> SqliteStore<K, V> store(@NotNull String name, @NotNull KeyCodec<K> codec,
                                                 @NotNull Serializer<V> serializer) {
        File dir = folder;
        if (stopping || dir == null) throw new IllegalStateException("Persistance arrêtée ou non initialisée");
        return (SqliteStore<K, V>) STORES.computeIfAbsent(name, n -> {
            SqliteStore<K, V> store = new SqliteStore<>(new File(dir, n + ".db"), n, codec, serializer);
            submit(store::initialize);
            return store;
        });
    }

    /**
     * Exécute {@code call} sur le writer et en renvoie le résultat. Sur le writer : appel direct ;
     * ailleurs : redirection + attente bornée (30 s). Après l'arrêt du writer : appel direct.
     * Thread: thread-safe. Usage interne des stores.
     */
    @ApiStatus.Internal
    public static <T> T onWriter(@NotNull String store, @NotNull String op, @NotNull SqlCall<T> call) {
        try {
            if (Thread.currentThread() == writer) return call.call();
            ThreadPoolExecutor ex = io;
            if (ex == null && !stopping) ex = executor();
            if (ex == null || ex.isShutdown()) {
                synchronized (PersistenceManager.class) { return call.call(); }
            }
            if (runtime && !stopping && Platform.isPrimaryThread() && WARNED.add(store + '/' + op)) {
                Log.warning("I/O SQLite synchrone sur le main thread (" + store + "." + op
                        + ") : utiliser PersistenceManager.async(...)");
            }
            Future<T> f = ex.submit(call::call);
            return f.get(BLOCKING_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException(op + " / store " + store, e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException(op + " / store " + store + " : writer bloqué > " + BLOCKING_TIMEOUT_MS + " ms", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(op + " / store " + store + " : interrompu", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(op + " / store " + store, e);
        }
    }

    /** Appel SQLite pouvant lever une exception vérifiée. */
    @FunctionalInterface
    public interface SqlCall<T> {
        T call() throws Exception;
    }

    // ─── écritures ──────────────────────────────────────────────────────────

    /**
     * Soumet une écriture au thread I/O. Seule voie pour les écritures runtime.
     * Les exceptions de la tâche sont journalisées en SEVERE. Depuis le writer : exécution directe.
     * Après {@link #beginShutdown()} et tant que le writer tourne (fenêtre du disable) : écriture
     * synchrone bornée, jamais perdue. Refus (persistance fermée, file pleine) journalisé et
     * signalé par {@code false}. Thread: thread-safe.
     */
    public static boolean async(@NotNull Runnable work) {
        if (Thread.currentThread() == writer) { // déjà sur le writer (hook d'autosave…) : exécution directe
            safe(work);
            return true;
        }
        if (stopping) {
            ThreadPoolExecutor ex = io;
            if (ex != null && !ex.isShutdown()) {
                // fenêtre du disable : le writer tourne encore, l'écriture est faite tout de suite
                // (synchrone, bornée) plutôt que perdue
                try {
                    onWriter("async", "disable", () -> { work.run(); return null; });
                    return true;
                } catch (RuntimeException e) {
                    Log.severe("écriture du disable", e);
                    return false;
                }
            }
            Log.warning("écriture refusée : persistance déjà fermée");
            return false;
        }
        ThreadPoolExecutor ex = executor();
        if (ex.getQueue().size() >= MAX_QUEUE) {
            Log.severe("écriture refusée : file I/O pleine (" + MAX_QUEUE + ")", new RejectedExecutionException());
            return false;
        }
        try {
            ex.execute(() -> safe(work));
            return true;
        } catch (RejectedExecutionException e) {
            Log.severe("écriture refusée", e);
            return false;
        }
    }

    /** Comme {@link #async} mais lève {@link RejectedExecutionException} (lots à réessayer). */
    @ApiStatus.Internal
    public static void submit(@NotNull Runnable work) {
        if (stopping) throw new RejectedExecutionException("Persistance arrêtée");
        ThreadPoolExecutor ex = executor();
        if (ex.getQueue().size() >= MAX_QUEUE) throw new RejectedExecutionException("File I/O pleine");
        ex.execute(() -> safe(work));
    }

    /**
     * Autosave : programme l'écriture des profils en RAM (lots) et exécute les hooks sur le writer.
     * Thread: main-thread-only.
     */
    public static void flushAll() {
        if (stopping) return;
        BINDINGS.forEach(PlayerBinding::flushAll);
        if (!AUTOSAVE_HOOKS.isEmpty()) {
            List<Runnable> hooks = List.copyOf(AUTOSAVE_HOOKS);
            async(() -> hooks.forEach(PersistenceManager::safe));
        }
    }

    /**
     * Hook exécuté à chaque autosave et au shutdown, <b>sur le writer</b> : ne lire que de l'état
     * thread-safe. Pour un snapshot d'état vivant, préférer {@link SnapshotWriter}. Thread: thread-safe.
     */
    public static void onAutoSave(@NotNull Runnable hook) {
        if (!AUTOSAVE_HOOKS.contains(hook)) AUTOSAVE_HOOKS.add(hook);
    }

    /** Thread: thread-safe. */
    public static boolean removeAutoSave(@NotNull Runnable hook) {
        return AUTOSAVE_HOOKS.remove(hook);
    }

    // ─── données joueur ─────────────────────────────────────────────────────

    /** Binding pour valeurs immuables. Thread: main-thread-only. */
    @NotNull
    public static <V> PlayerBinding<V> bindPlayer(@NotNull ManagedCache<UUID, V> cache,
                                                  @NotNull PersistentStore<UUID, V> store,
                                                  @Nullable Function<UUID, V> defaults) {
        return bindPlayer(cache, store, defaults, UnaryOperator.identity(), Objects::equals);
    }

    /**
     * @param snapshot copie détachée d'une valeur mutable (prise sur le main thread).
     * @param same     compare l'état courant au dernier écrit (éviction seulement si inchangé).
     *                 Thread: main-thread-only.
     */
    @NotNull
    public static <V> PlayerBinding<V> bindPlayer(@NotNull ManagedCache<UUID, V> cache,
                                                  @NotNull PersistentStore<UUID, V> store,
                                                  @Nullable Function<UUID, V> defaults,
                                                  @NotNull UnaryOperator<V> snapshot,
                                                  @NotNull BiPredicate<V, V> same) {
        if (stopping) throw new IllegalStateException("Persistance arrêtée");
        PlayerBinding<V> binding = new PlayerBinding<>(cache, store, defaults, snapshot, same);
        BINDINGS.removeIf(b -> b.cache() == cache);
        BINDINGS.add(binding);
        return binding;
    }

    /** Thread: main-thread-only. */
    public static boolean unbindPlayer(@NotNull ManagedCache<UUID, ?> cache) {
        return BINDINGS.removeIf(b -> b.cache() == cache);
    }

    /** Join : programme le chargement. Thread: main-thread-only. */
    public static void loadPlayer(@NotNull UUID id) {
        if (stopping) return;
        for (PlayerBinding<?> b : BINDINGS) {
            if (!b.load(id)) Log.warning("capacité des profils atteinte pour '" + b.storeName() + "', chargement refusé : " + id);
        }
    }

    /** Thread: main-thread-only (ex. joueurs déjà en ligne après un reload). */
    public static void loadPlayers(@NotNull Collection<UUID> ids) {
        for (UUID id : ids) loadPlayer(id);
    }

    /** Quit : programme la sauvegarde puis l'éviction. Thread: main-thread-only. */
    public static void savePlayer(@NotNull UUID id) {
        if (stopping) return;
        for (PlayerBinding<?> b : BINDINGS) b.saveAndEvict(id);
    }

    // ─── entretien & diagnostic ─────────────────────────────────────────────

    /**
     * Entretien de tous les stores ouverts, une seule fois en <b>fin</b> d'{@code onEnable}.
     * Marque aussi la fin de la fenêtre de démarrage (au-delà, une I/O bloquante sur le main
     * thread est signalée). Bilan en une ligne. Thread: thread-safe.
     */
    public static void maintenance() {
        runtime = true;
        if (STORES.isEmpty()) return;
        async(() -> {
            long started = System.currentTimeMillis();
            long rows = 0;
            int compacted = 0;
            int broken = 0;
            for (SqliteStore<?, ?> store : STORES.values()) {
                try {
                    SqliteStore.Maintenance m = store.maintain();
                    rows += m.rows();
                    if (m.compacted()) compacted++;
                    if (!m.healthy()) {
                        broken++;
                        Log.warning("store '" + m.store() + "' signale une corruption (quick_check)");
                    }
                } catch (RuntimeException e) {
                    broken++;
                    Log.severe("entretien impossible sur '" + store.name() + "'", e);
                }
            }
            Log.info(STORES.size() + " store(s), " + rows + " ligne(s), " + compacted + " compacté(s), "
                    + broken + " en erreur (" + (System.currentTimeMillis() - started) + " ms)");
        });
    }

    /** @return {@code Data/<plugin>/}, ou {@code null} hors init/shutdown. */
    @Nullable
    public static File folder() { return folder; }

    public static int storeCount() { return STORES.size(); }

    public static int bindingCount() { return BINDINGS.size(); }

    public static int hookCount() { return AUTOSAVE_HOOKS.size(); }

    /** @return écritures en attente sur le writer (diagnostic). */
    public static int queueSize() {
        ThreadPoolExecutor ex = io;
        return ex == null ? 0 : ex.getQueue().size();
    }

    /** @return {@code true} dès {@link #beginShutdown()} : plus aucune entrée acceptée. */
    public static boolean isStopping() { return stopping; }

    /** @return {@code true} tant que la persistance accepte des entrées. */
    public static boolean isAccepting() { return !stopping; }

    /** @return noms des stores ouverts (diagnostic). */
    @NotNull
    public static List<String> storeNames() { return List.copyOf(STORES.keySet()); }

    /** Alias de {@link #isStopping()}. */
    public static boolean isShuttingDown() { return stopping; }

    // ─── interne ────────────────────────────────────────────────────────────

    private static synchronized @NotNull ThreadPoolExecutor executor() {
        ThreadPoolExecutor ex = io;
        if (ex != null) return ex;
        String name = "NightCache-" + Objects.requireNonNullElse(owner, "?") + "-io";
        ex = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), r -> {
            Thread t = new Thread(() -> {
                try {
                    r.run();
                } finally {
                    if (forceClose) closeStores();
                }
            }, name);
            t.setDaemon(true);
            t.setContextClassLoader(null); // ne retient pas le classloader du plugin
            writer = t;
            return t;
        });
        io = ex;
        return ex;
    }

    /** Sur le writer uniquement. */
    private static void closeStores() {
        for (SqliteStore<?, ?> store : STORES.values()) safe(store::close);
        SqliteConnections.release();
    }

    private static void safe(@NotNull Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            Log.severe("erreur de persistance", e);
        }
    }
}
