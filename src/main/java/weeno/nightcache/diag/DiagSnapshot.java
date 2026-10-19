package weeno.nightcache.diag;

import org.bukkit.Bukkit;
import weeno.nightcache.platform.Platform;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import weeno.nightcache.CacheManager;
import weeno.nightcache.objects.CacheStats;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.save.PlayerStates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Photo de tous les compteurs mémoire du plugin (caches, stores, états joueur, jauges des
 * managers, heap). Deux photos comparées via {@link #diff} révèlent ce qui grossit : une
 * structure qui ne redescend jamais entre deux captures est une fuite.
 */
public final class DiagSnapshot {

    private static final String VOLATILE_PREFIX = "heap:";
    private static final Map<String, LongSupplier> GAUGES = new ConcurrentHashMap<>(32);

    private final long time;
    private final Map<String, Long> counters;

    private DiagSnapshot(long time, @NotNull Map<String, Long> counters) {
        this.time = time;
        this.counters = counters;
    }

    /**
     * Déclare une jauge métier ({@code manager:hoppers}, {@code manager:chaudrons}…) lue à
     * chaque capture. Remplace une jauge existante du même nom.
     */
    public static void gauge(@NotNull String name, @NotNull LongSupplier supplier) {
        GAUGES.put(name, supplier);
    }

    /** Une jauge relevée. */
    public record Gauge(@NotNull String name, long value) {}

    /** @return relevé de toutes les jauges ; une jauge en erreur vaut {@code -1}. */
    @NotNull
    @Unmodifiable
    public static List<Gauge> read() {
        List<Gauge> out = new ArrayList<>(GAUGES.size());
        for (Map.Entry<String, LongSupplier> e : new TreeMap<>(GAUGES).entrySet()) {
            long v;
            try {
                v = e.getValue().getAsLong();
            } catch (RuntimeException ex) {
                v = -1L;
            }
            out.add(new Gauge(e.getKey(), v));
        }
        return Collections.unmodifiableList(out);
    }

    /** @return nombre de jauges enregistrées. */
    public static int size() { return GAUGES.size(); }

    /** Retire une jauge (module arrêté à chaud). */
    public static void remove(@NotNull String name) {
        GAUGES.remove(name);
    }

    /** Vide les jauges (appelé au {@code onDisable} : elles capturent des managers). */
    public static void clearGauges() {
        GAUGES.clear();
    }

    /** Alias de {@link #clearGauges()}. */
    public static void clear() {
        GAUGES.clear();
    }

    /** @return les compteurs d'une capture immédiate. */
    @NotNull
    @Unmodifiable
    public static Map<String, Long> snapshot() {
        return capture().counters();
    }

    @NotNull
    public static DiagSnapshot capture() {
        Map<String, Long> map = new TreeMap<>();

        for (CacheStats stats : CacheManager.stats()) {
            map.put("cache:" + stats.name(), stats.estimatedSize());
        }

        map.put("registry:caches", (long) CacheManager.cacheCount());
        map.put("registry:pools", (long) CacheManager.poolCount());
        map.put("registry:asyncLeaderboards", (long) CacheManager.asyncLeaderboardCount());
        map.put("persist:stores", (long) PersistenceManager.storeCount());
        map.put("persist:bindings", (long) PersistenceManager.bindingCount());
        map.put("persist:autosaveHooks", (long) PersistenceManager.hookCount());
        map.put("persist:queue", (long) PersistenceManager.queueSize());
        map.put("state:trackedMaps", (long) PlayerStates.trackedMaps());
        map.put("state:trackedEntries", (long) PlayerStates.trackedEntries());
        map.put("state:onlinePlayers", (long) Bukkit.getOnlinePlayers().size());

        for (Map.Entry<String, LongSupplier> e : GAUGES.entrySet()) {
            try {
                map.put(e.getKey(), e.getValue().getAsLong());
            } catch (RuntimeException ex) {
                // jauge d'un manager en cours d'arrêt : valeur sentinelle plutôt qu'un crash du diag
                map.put(e.getKey(), -1L);
            }
        }

        org.bukkit.plugin.Plugin plugin = CacheManager.plugin();
        if (plugin != null) {
            map.put("bukkit:registeredListeners", (long) HandlerList.getRegisteredListeners(plugin).size());
            if (!Platform.folia()) { // pas de scheduler Bukkit classique sur Folia
                map.put("bukkit:pendingTasks", Bukkit.getScheduler().getPendingTasks().stream()
                        .filter(t -> t.getOwner() == plugin).count());
            }
        }

        Runtime rt = Runtime.getRuntime();
        map.put("heap:usedMB", (rt.totalMemory() - rt.freeMemory()) >> 20);
        map.put("heap:committedMB", rt.totalMemory() >> 20);
        map.put("heap:maxMB", rt.maxMemory() >> 20);
        return new DiagSnapshot(System.currentTimeMillis(), Collections.unmodifiableMap(map));
    }

    @Contract(pure = true) public long time() { return time; }

    @NotNull @Unmodifiable @Contract(pure = true)
    public Map<String, Long> counters() { return counters; }

    /** @return les compteurs qui ont changé depuis {@code base} (heap exclu : trop volatil). */
    @NotNull
    @Unmodifiable
    public List<DiagDelta> diff(@NotNull DiagSnapshot base) {
        List<DiagDelta> out = new ArrayList<>(16);
        for (String key : union(base)) {
            if (key.startsWith(VOLATILE_PREFIX)) continue;
            long before = base.counters.getOrDefault(key, 0L);
            long after = counters.getOrDefault(key, 0L);
            if (after != before) out.add(new DiagDelta(key, before, after));
        }
        return Collections.unmodifiableList(out);
    }

    @Contract(pure = true)
    public long get(@NotNull String key) {
        return counters.getOrDefault(key, 0L);
    }

    @NotNull
    private TreeSet<String> union(@NotNull DiagSnapshot other) {
        TreeSet<String> keys = new TreeSet<>(counters.keySet());
        keys.addAll(other.counters.keySet());
        return keys;
    }
}
