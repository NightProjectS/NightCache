package weeno.nightcache;

import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import weeno.nightcache.caches.AsyncLeaderboard;
import weeno.nightcache.caches.Leaderboard;
import weeno.nightcache.caches.LongObjectCache;
import weeno.nightcache.caches.ObjectLongCache;
import weeno.nightcache.database.SqliteConnections;
import weeno.nightcache.database.SqliteStore;
import weeno.nightcache.diag.DiagSnapshot;
import weeno.nightcache.interfaces.ManagedCache;
import weeno.nightcache.interfaces.ObjectPool;
import weeno.nightcache.listeners.DataListener;
import weeno.nightcache.objects.CacheSpec;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.save.PlayerBinding;
import weeno.nightcache.save.PlayerStates;
import weeno.nightcache.save.SnapshotWriter;
import weeno.nightcache.testsupport.FakeServer;
import weeno.nightcache.utils.GsonSerializer;
import weeno.nightcache.utils.JsonRow;
import weeno.nightcache.utils.KeyCodec;
import weeno.nightcache.utils.Log;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Cycle de vie complet d'un plugin, exécuté DANS un classloader de plugin simulé (voir {@code ClassLoaderLeakTest}) :
 * enable, tous les types de caches, stores, binding joueur (join / quit via le listener), hooks, jauges, leaderboard
 * async, maintenance, disable, puis un second enable/disable (reload) qui relit les données. Ne renvoie que des
 * objets du JDK (aucune référence vers ce classloader).
 */
public final class LeakScenario implements Function<String, Map<String, Object>> {

    /** Valeur sérialisée par Gson (record : exige le Gson moderne ou un objet simple sur 1.8). */
    public static final class Stats {
        int kills;
        String title;
    }

    @Override
    public Map<String, Object> apply(String pluginName) {
        Map<String, Object> out = new HashMap<>();
        FakeServer.install();
        Plugin plugin = FakeServer.plugin(pluginName);

        NightCache.enable(plugin);
        ManagedCache<UUID, JsonRow> profiles = CacheManager.cache(CacheSpec.persistent("profiles"));
        SqliteStore<UUID, JsonRow> store = PersistenceManager.store("profiles", KeyCodec.uuid(), JsonRow.SERIALIZER);
        PlayerBinding<JsonRow> binding = PersistenceManager.bindPlayer(profiles, store, id -> JsonRow.create().put("n", 0L));
        SqliteStore<String, Stats> stats = PersistenceManager.store("stats", KeyCodec.string(), GsonSerializer.of(Stats.class));
        SnapshotWriter<String, Stats> snapshots = new SnapshotWriter<>(stats);
        snapshots.markReady();

        LongObjectCache<String> chunks = CacheManager.longCache("chunks", 100, 60_000);
        chunks.put(1L, "a");
        ObjectLongCache<UUID> cooldowns = CacheManager.objectLongCache("cooldowns", 100, 60_000, 0);
        Leaderboard<String> board = CacheManager.leaderboard("board");
        board.set("x", 1);
        AsyncLeaderboard<String> async = CacheManager.asyncLeaderboard("async", 5, 1);
        async.add("x", 5);
        ObjectPool<StringBuilder> pool = CacheManager.pool("buffers", StringBuilder::new, sb -> sb.setLength(0), 4);
        pool.release(pool.borrow());
        Map<UUID, String> menus = PlayerStates.track(new ConcurrentHashMap<>());
        PlayerStates.onQuit(id -> menus.remove(id));
        DiagSnapshot.gauge("manager:test", menus::size);
        PersistenceManager.onAutoSave(() -> chunks.getIfPresent(1L));

        UUID alice = new UUID(1, 1);
        UUID bob = new UUID(2, 2);
        DataListener listener = new DataListener();
        listener.onJoin(new PlayerJoinEvent(player(alice), "join"));
        listener.onJoin(new PlayerJoinEvent(player(bob), "join"));
        await(() -> binding.get(alice) != null && binding.get(bob) != null, "chargement des joueurs");
        profiles.put(alice, JsonRow.create().put("n", 10L));
        profiles.put(bob, JsonRow.create().put("n", 20L).put("nom", "Bob ✓"));
        cooldowns.addTo(alice, 3);
        menus.put(alice, "menu");
        listener.onQuit(new PlayerQuitEvent(player(alice), "quit"));
        await(() -> profiles.getIfPresent(alice) == null, "sauvegarde puis éviction au quit");
        out.put("menuPurgedOnQuit", !menus.containsKey(alice));
        Stats s = new Stats();
        s.kills = 7;
        s.title = "Légende";
        snapshots.submit(Map.of("bob", s));
        await(() -> !async.getTop().isEmpty(), "leaderboard async rafraîchi");
        PersistenceManager.maintenance();
        out.put("isolatedLoader", new WeakReference<>(isolated()));

        PersistenceManager.beginShutdown();
        snapshots.writeNow(Map.of("bob", s));
        NightCache.disable();
        out.putAll(registries("afterDisable."));
        out.put("ownDrivers", ownDrivers());

        // Reload : même plugin, données relues depuis le disque.
        NightCache.enable(plugin);
        SqliteStore<UUID, JsonRow> again = PersistenceManager.store("profiles", KeyCodec.uuid(), JsonRow.SERIALIZER);
        out.put("alice", again.load(alice).l("n"));
        out.put("bob", again.load(bob).l("n"));
        out.put("bobName", again.load(bob).getString("nom"));
        Stats read = PersistenceManager.store("stats", KeyCodec.string(), GsonSerializer.of(Stats.class)).load("bob");
        out.put("bobKills", read == null ? -1 : read.kills);
        out.put("bobTitle", read == null ? null : read.title);
        PersistenceManager.beginShutdown();
        NightCache.disable();
        out.putAll(registries("afterReload."));
        out.put("ownDriversAfterReload", ownDrivers());
        return out;
    }

    private static Map<String, Object> registries(String prefix) {
        Map<String, Object> m = new HashMap<>();
        m.put(prefix + "caches", CacheManager.cacheCount());
        m.put(prefix + "pools", CacheManager.poolCount());
        m.put(prefix + "asyncLeaderboards", CacheManager.asyncLeaderboardCount());
        m.put(prefix + "stores", PersistenceManager.storeCount());
        m.put(prefix + "bindings", PersistenceManager.bindingCount());
        m.put(prefix + "hooks", PersistenceManager.hookCount());
        m.put(prefix + "trackedMaps", PlayerStates.trackedMaps());
        m.put(prefix + "gauges", DiagSnapshot.size());
        m.put(prefix + "plugin", CacheManager.plugin() == null ? "null" : "set");
        m.put(prefix + "log", Log.ownerOrNull() == null ? "null" : Log.ownerOrNull());
        m.put(prefix + "folder", PersistenceManager.folder() == null ? "null" : "set");
        m.put(prefix + "isolated", isolated() == null ? "null" : "set");
        return m;
    }

    private static Object isolated() {
        try {
            Field f = SqliteConnections.class.getDeclaredField("isolated");
            f.setAccessible(true);
            return f.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e.toString());
        }
    }

    /** Pilotes JDBC chargés par CE classloader ou un de ses enfants, encore enregistrés. */
    private static List<String> ownDrivers() {
        List<String> out = new ArrayList<>();
        ClassLoader own = LeakScenario.class.getClassLoader();
        Enumeration<Driver> all = DriverManager.getDrivers();
        while (all.hasMoreElements()) {
            Driver d = all.nextElement();
            for (ClassLoader l = d.getClass().getClassLoader(); l != null; l = l.getParent()) {
                if (l == own) out.add(d.getClass().getName());
            }
        }
        return out;
    }

    private static void await(BooleanSupplier done, String what) {
        long end = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new IllegalStateException("délai dépassé : " + what);
            FakeServer.tick();
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrompu");
            }
        }
    }

    private static Player player(UUID id) {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class}, (p, m, a) -> switch (m.getName()) {
            case "getUniqueId" -> id;
            case "getName" -> "p" + id.getLeastSignificantBits();
            case "hashCode" -> id.hashCode();
            case "equals" -> p == a[0];
            case "toString" -> "Player[" + id + "]";
            default -> m.getReturnType() == boolean.class ? false : m.getReturnType().isPrimitive() && m.getReturnType() != void.class ? 0 : null;
        });
    }
}
