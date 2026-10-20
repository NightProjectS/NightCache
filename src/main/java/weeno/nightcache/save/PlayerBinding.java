package weeno.nightcache.save;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.interfaces.ManagedCache;
import weeno.nightcache.interfaces.PersistentStore;
import weeno.nightcache.utils.Log;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.*;

/** Main-thread lifecycle; only detached values cross into the I/O executor. */
public final class PlayerBinding<V> {
    private static final int BATCH_SIZE = 128;
    private static final int MAX_PROFILES = 20_000;
    private static final long RETRY_NANOS = 5_000_000_000L;
    private final ManagedCache<UUID, V> cache;
    private final PersistentStore<UUID, V> store;
    private final Function<UUID, V> defaults;
    private final UnaryOperator<V> snapshot;
    private final BiPredicate<V, V> same;
    private final LongSupplier clock;
    private final Set<UUID> online = new HashSet<>();
    private final Set<UUID> admitted = new HashSet<>();
    private final Map<UUID, Integer> pins = new HashMap<>();
    private final Set<UUID> loads = new LinkedHashSet<>();
    private final Set<UUID> saves = new LinkedHashSet<>();
    private final ConcurrentLinkedQueue<Result<V>> completed = new ConcurrentLinkedQueue<>();
    private boolean inFlight;
    private long retryAt;

    /** main-thread-only; snapshot must detach mutable values and same must compare persisted state. */
    PlayerBinding(@NotNull ManagedCache<UUID, V> cache, @NotNull PersistentStore<UUID, V> store,
                  @Nullable Function<UUID, V> defaults, @NotNull UnaryOperator<V> snapshot,
                  @NotNull BiPredicate<V, V> same) {
        this(cache, store, defaults, snapshot, same, System::nanoTime);
    }

    /** main-thread-only */
    PlayerBinding(@NotNull ManagedCache<UUID, V> cache, @NotNull PersistentStore<UUID, V> store,
                  @Nullable Function<UUID, V> defaults, @NotNull UnaryOperator<V> snapshot,
                  @NotNull BiPredicate<V, V> same, @NotNull LongSupplier clock) {
        this.cache = cache;
        this.store = store;
        this.defaults = defaults;
        this.snapshot = snapshot;
        this.same = same;
        this.clock = clock;
        this.retryAt = clock.getAsLong();
    }

    /** main-thread-only */
    @NotNull ManagedCache<UUID, V> cache() { return cache; }

    /** thread-safe */
    @NotNull String storeName() { return store.name(); }

    /** main-thread-only (diagnostic) */
    public int pending() { return loads.size() + saves.size(); }

    /** main-thread-only */
    public boolean load(@NotNull UUID id) {
        if (!admitted.contains(id) && admitted.size() >= MAX_PROFILES && purgeAdmitted() >= MAX_PROFILES) return false;
        admitted.add(id);
        online.add(id);
        if (cache.getIfPresent(id) == null) loads.add(id);
        return true;
    }

    /** main-thread-only */
    public @Nullable V get(@NotNull UUID id) { return cache.getIfPresent(id); }

    /** main-thread-only; a pending game may retain a loaded profile after logout. */
    public boolean retain(@NotNull UUID id) {
        if (cache.getIfPresent(id) == null) return false;
        pins.merge(id, 1, Math::addExact);
        return true;
    }

    /** main-thread-only */
    public void release(@NotNull UUID id) {
        pins.computeIfPresent(id, (k, n) -> n == 1 ? null : n - 1);
        if (!online.contains(id) && !pins.containsKey(id)) save(id);
    }

    /** main-thread-only */
    public void save(@NotNull UUID id) {
        if (cache.getIfPresent(id) != null) saves.add(id);
    }

    /** main-thread-only; eviction happens only after a successful, still-current write. */
    public void saveAndEvict(@NotNull UUID id) {
        online.remove(id);
        loads.remove(id);
        if (cache.getIfPresent(id) == null) admitted.remove(id);
        if (!pins.containsKey(id)) save(id);
    }

    /** main-thread-only; rien à faire (aucun profil en RAM) : aucune allocation. */
    public void flushAll() {
        if (!cache.asMap().isEmpty()) saves.addAll(cache.asMap().keySet());
    }

    /** main-thread-only; at most one batch and one completion per binding. */
    public void tick(@NotNull Consumer<Runnable> executor) {
        Result<V> result;
        while ((result = completed.poll()) != null) apply(result);
        // Cas courant (rien en attente) : retour immédiat, aucune allocation à chaque tick.
        if (inFlight || (loads.isEmpty() && saves.isEmpty()) || clock.getAsLong() - retryAt < 0) return;
        List<UUID> read = loads.isEmpty() ? List.of() : new ArrayList<>(Math.min(BATCH_SIZE, loads.size()));
        for (Iterator<UUID> it = loads.iterator(); it.hasNext() && read.size() < BATCH_SIZE;) {
            UUID id = it.next();
            it.remove();
            if (wanted(id) && cache.getIfPresent(id) == null) read.add(id);
        }
        Map<UUID, V> write = saves.isEmpty() ? Map.of() : new LinkedHashMap<>(capacityFor(Math.min(BATCH_SIZE, saves.size())));
        List<UUID> unsnapshotted = null;
        for (Iterator<UUID> it = saves.iterator(); it.hasNext() && write.size() < BATCH_SIZE;) {
            UUID id = it.next();
            it.remove();
            V value = cache.getIfPresent(id);
            if (value == null) {
                // Profil sorti du cache (TTL, invalidate) avant son écriture : rien à écrire, plus rien à retenir.
                if (!wanted(id)) admitted.remove(id);
                continue;
            }
            try {
                write.put(id, snapshot.apply(value));
            } catch (RuntimeException e) {
                // Une copie impossible ne fait pas perdre le reste du lot : ce profil est réessayé plus tard.
                Log.severe("copie du profil " + id + " pour " + store.name(), e);
                if (unsnapshotted == null) unsnapshotted = new ArrayList<>();
                unsnapshotted.add(id);
            }
        }
        if (unsnapshotted != null) {
            saves.addAll(unsnapshotted);
            retryAt = clock.getAsLong() + RETRY_NANOS;
        }
        if (read.isEmpty() && write.isEmpty()) return;
        inFlight = true;
        try {
            executor.accept(() -> execute(read, write));
        } catch (RuntimeException e) {
            inFlight = false;
            loads.addAll(read);
            saves.addAll(write.keySet());
            retryAt = clock.getAsLong() + RETRY_NANOS;
            Log.severe("lot refusé pour " + store.name(), e);
        }
    }

    /** async-safe; detached inputs, no Bukkit access. */
    private void execute(@NotNull List<UUID> read, @NotNull Map<UUID, V> write) {
        Map<UUID, V> loaded = new HashMap<>();
        Set<UUID> failed = new HashSet<>();
        Map<UUID, V> saved = Map.of();
        try {
            for (UUID id : read) {
                try {
                    V value = store.load(id);
                    if (value == null && defaults != null) value = defaults.apply(id);
                    if (value != null) loaded.put(id, value);
                } catch (Exception e) {
                    failed.add(id);
                    Log.severe("chargement " + store.name() + " / " + id, e);
                }
            }
            saved = saveIsolating(write);
        } catch (Exception e) {
            Log.severe("sauvegarde " + store.name() + " / " + write.size() + " profil(s)", e);
        } finally {
            completed.add(new Result<>(loaded, failed, write, saved));
        }
    }

    /**
     * async-safe; écrit le lot en une transaction et, s'il échoue, profil par profil : un seul profil impossible à
     * écrire (valeur non sérialisable…) ne bloque plus les autres du lot.
     *
     * @return les profils effectivement écrits.
     */
    private @NotNull Map<UUID, V> saveIsolating(@NotNull Map<UUID, V> write) {
        if (write.isEmpty()) return write;
        try {
            store.saveAll(write);
            return write;
        } catch (RuntimeException batch) {
            if (write.size() == 1) throw batch;
            Map<UUID, V> ok = new HashMap<>(capacityFor(write.size()));
            RuntimeException first = null;
            for (Map.Entry<UUID, V> e : write.entrySet()) {
                try {
                    store.save(e.getKey(), e.getValue());
                    ok.put(e.getKey(), e.getValue());
                } catch (RuntimeException one) {
                    if (first == null) first = one;
                }
            }
            if (first != null) {
                Log.severe("sauvegarde " + store.name() + " : " + (write.size() - ok.size()) + " profil(s) sur "
                        + write.size() + " non écrit(s), nouvel essai plus tard", first);
            }
            return ok;
        }
    }

    /** main-thread-only */
    private void apply(@NotNull Result<V> result) {
        inFlight = false;
        result.loaded().forEach((id, value) -> {
            if (wanted(id)) cache.asMap().putIfAbsent(id, value);
        });
        result.failed().forEach(id -> { if (wanted(id)) loads.add(id); });
        Map<UUID, V> saved = result.saved();
        boolean partial = saved.size() < result.written().size();
        if (partial) {
            for (UUID id : result.written().keySet()) if (!saved.containsKey(id)) saves.add(id);
        }
        saved.forEach((id, value) -> {
            if (wanted(id)) return;
            V current = cache.getIfPresent(id);
            if (current == null) admitted.remove(id); // sorti du cache pendant l'écriture
            else if (same.test(current, value)) { cache.invalidate(id); admitted.remove(id); }
            else saves.add(id);
        });
        if (partial || !result.failed().isEmpty()) retryAt = clock.getAsLong() + RETRY_NANOS;
    }

    /** main-thread-only */
    private boolean wanted(@NotNull UUID id) { return online.contains(id) || pins.containsKey(id); }

    /**
     * main-thread-only; rare (table pleine) : retire les admissions qui ne correspondent plus à rien (ni en ligne, ni
     * retenues, ni en RAM). Filet de sécurité contre une table remplie par des profils sortis du cache autrement.
     *
     * @return taille de la table après purge.
     */
    private int purgeAdmitted() {
        admitted.removeIf(id -> !wanted(id) && cache.getIfPresent(id) == null);
        return admitted.size();
    }

    /** Capacité initiale d'une table de hachage pour {@code n} entrées sans redimensionnement (facteur 0,75). */
    private static int capacityFor(int n) { return (int) (n / 0.75f) + 1; }

    /**
     * main-thread-only; returned job is async-safe and contains detached values only. Un profil dont la copie ou
     * l'écriture échoue est journalisé sans empêcher la sauvegarde finale des autres.
     */
    public @NotNull Runnable finalSave() {
        Map<UUID, V> values = new LinkedHashMap<>();
        cache.asMap().forEach((id, value) -> {
            try {
                values.put(id, snapshot.apply(value));
            } catch (RuntimeException e) {
                Log.severe("copie du profil " + id + " pour la sauvegarde finale de " + store.name(), e);
            }
        });
        return () -> {
            Map<UUID, V> batch = new LinkedHashMap<>();
            int lost = 0;
            for (var entry : values.entrySet()) {
                batch.put(entry.getKey(), entry.getValue());
                if (batch.size() == BATCH_SIZE) { lost += finalBatch(batch); batch.clear(); }
            }
            lost += finalBatch(batch);
            if (lost > 0) Log.warning("sauvegarde finale de " + store.name() + " : " + lost + " profil(s) non écrit(s)");
        };
    }

    /** writer; @return nombre de profils non écrits. */
    private int finalBatch(@NotNull Map<UUID, V> batch) {
        if (batch.isEmpty()) return 0;
        try {
            return batch.size() - saveIsolating(batch).size();
        } catch (RuntimeException e) {
            Log.severe("sauvegarde finale " + store.name() + " / " + batch.size() + " profil(s)", e);
            return batch.size();
        }
    }

    private record Result<V>(Map<UUID, V> loaded, Set<UUID> failed, Map<UUID, V> written, Map<UUID, V> saved) {}
}
