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
        if (!admitted.contains(id) && admitted.size() >= MAX_PROFILES) return false;
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

    /** main-thread-only */
    public void flushAll() { saves.addAll(cache.asMap().keySet()); }

    /** main-thread-only; at most one batch and one completion per binding. */
    public void tick(@NotNull Consumer<Runnable> executor) {
        Result<V> result;
        while ((result = completed.poll()) != null) apply(result);
        if (inFlight || clock.getAsLong() - retryAt < 0) return;
        List<UUID> read = new ArrayList<>(BATCH_SIZE);
        for (Iterator<UUID> it = loads.iterator(); it.hasNext() && read.size() < BATCH_SIZE;) {
            UUID id = it.next();
            it.remove();
            if (wanted(id) && cache.getIfPresent(id) == null) read.add(id);
        }
        Map<UUID, V> write = new LinkedHashMap<>();
        for (Iterator<UUID> it = saves.iterator(); it.hasNext() && write.size() < BATCH_SIZE;) {
            UUID id = it.next();
            it.remove();
            V value = cache.getIfPresent(id);
            if (value != null) write.put(id, snapshot.apply(value));
        }
        if (read.isEmpty() && write.isEmpty()) return;
        inFlight = true;
        try {
            executor.accept(() -> execute(read, write));
        } catch (RuntimeException e) {
            inFlight = false;
            loads.addAll(read);
            saves.addAll(write.keySet());
            retryAt = clock.getAsLong() + 5_000_000_000L;
            Log.severe("lot refusé pour " + store.name(), e);
        }
    }

    /** async-safe; detached inputs, no Bukkit access. */
    private void execute(@NotNull List<UUID> read, @NotNull Map<UUID, V> write) {
        Map<UUID, V> loaded = new HashMap<>();
        Set<UUID> failed = new HashSet<>();
        boolean saved = false;
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
            store.saveAll(write);
            saved = true;
        } catch (Exception e) {
            Log.severe("sauvegarde " + store.name() + " / " + write.size() + " profil(s)", e);
        } finally {
            completed.add(new Result<>(loaded, failed, write, saved));
        }
    }

    /** main-thread-only */
    private void apply(@NotNull Result<V> result) {
        inFlight = false;
        result.loaded().forEach((id, value) -> {
            if (wanted(id)) cache.asMap().putIfAbsent(id, value);
        });
        result.failed().forEach(id -> { if (wanted(id)) loads.add(id); });
        if (!result.saved()) {
            saves.addAll(result.written().keySet());
        } else {
            result.written().forEach((id, value) -> {
                V current = cache.getIfPresent(id);
                if (current != null && !wanted(id)) {
                    if (same.test(current, value)) { cache.invalidate(id); admitted.remove(id); }
                    else saves.add(id);
                }
            });
        }
        if (!result.saved() || !result.failed().isEmpty()) retryAt = clock.getAsLong() + 5_000_000_000L;
    }

    /** main-thread-only */
    private boolean wanted(@NotNull UUID id) { return online.contains(id) || pins.containsKey(id); }

    /** main-thread-only; returned job is async-safe and contains detached values only. */
    public @NotNull Runnable finalSave() {
        Map<UUID, V> values = new LinkedHashMap<>();
        cache.asMap().forEach((id, value) -> values.put(id, snapshot.apply(value)));
        return () -> {
            Map<UUID, V> batch = new LinkedHashMap<>();
            for (var entry : values.entrySet()) {
                batch.put(entry.getKey(), entry.getValue());
                if (batch.size() == BATCH_SIZE) { store.saveAll(batch); batch.clear(); }
            }
            store.saveAll(batch);
        };
    }

    private record Result<V>(Map<UUID, V> loaded, Set<UUID> failed, Map<UUID, V> written, boolean saved) {}
}
