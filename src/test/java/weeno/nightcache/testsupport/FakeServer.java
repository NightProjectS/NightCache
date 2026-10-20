package weeno.nightcache.testsupport;

import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Serveur Bukkit factice (proxies) installé une fois par JVM : scheduler qui compte les tâches (et les oublie à
 * l'annulation, pour ne retenir aucun objet d'un plugin), gestionnaire de plugins qui compte les listeners.
 */
public final class FakeServer {

    private static final Map<Integer, Runnable> TASKS = new ConcurrentHashMap<>();
    private static final AtomicInteger IDS = new AtomicInteger();
    private static final AtomicInteger LISTENERS = new AtomicInteger();
    private static final Logger LOG = Logger.getLogger("FakeServer");

    private FakeServer() {}

    public static synchronized void install() {
        if (Bukkit.getServer() != null) return;
        BukkitScheduler scheduler = proxy(BukkitScheduler.class, (p, m, a) -> switch (m.getName()) {
            case "runTaskTimer" -> schedule((Plugin) a[0], (Runnable) a[1]);
            case "cancelTask" -> { TASKS.remove((Integer) a[0]); yield null; }
            case "cancelTasks" -> { TASKS.clear(); yield null; }
            case "getPendingTasks" -> Collections.emptyList();
            default -> defaults(m.getReturnType());
        });
        PluginManager pm = proxy(PluginManager.class, (p, m, a) -> {
            if (m.getName().equals("registerEvents")) LISTENERS.incrementAndGet();
            return defaults(m.getReturnType());
        });
        Server server = proxy(Server.class, (p, m, a) -> switch (m.getName()) {
            case "getLogger" -> LOG;
            case "getName", "getVersion", "getBukkitVersion" -> "fake";
            case "getScheduler" -> scheduler;
            case "getPluginManager" -> pm;
            case "getOnlinePlayers" -> m.getReturnType().isArray()
                    ? java.lang.reflect.Array.newInstance(m.getReturnType().getComponentType(), 0) : Collections.emptyList();
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> "FakeServer";
            default -> defaults(m.getReturnType());
        });
        Bukkit.setServer(server);
    }

    /** Plugin factice : nom et logger seulement. */
    public static Plugin plugin(String name) {
        Logger log = Logger.getLogger("plugin." + name);
        return proxy(Plugin.class, (p, m, a) -> switch (m.getName()) {
            case "getName" -> name;
            case "getLogger" -> log;
            case "isEnabled" -> true;
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "toString" -> "Plugin[" + name + "]";
            default -> defaults(m.getReturnType());
        });
    }

    /** Exécute une fois toutes les tâches répétées encore planifiées (un « tick »). */
    public static void tick() {
        List<Runnable> all = new ArrayList<>(TASKS.values());
        for (Runnable r : all) r.run();
    }

    /** @return tâches planifiées et non annulées. */
    public static int pendingTasks() {
        return TASKS.size();
    }

    public static int registeredListeners() {
        return LISTENERS.get();
    }

    private static BukkitTask schedule(Plugin owner, Runnable task) {
        int id = IDS.incrementAndGet();
        TASKS.put(id, task);
        return proxy(BukkitTask.class, (p, m, a) -> switch (m.getName()) {
            case "getTaskId" -> id;
            case "cancel" -> { TASKS.remove(id); yield null; }
            case "isSync" -> true;
            case "hashCode" -> id;
            case "equals" -> p == a[0];
            case "toString" -> "Task#" + id;
            default -> defaults(m.getReturnType());
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler h) {
        return (T) Proxy.newProxyInstance(FakeServer.class.getClassLoader(), new Class<?>[]{type}, h);
    }

    private static Object defaults(Class<?> t) {
        if (!t.isPrimitive() || t == void.class) return null;
        if (t == boolean.class) return false;
        if (t == long.class) return 0L;
        if (t == double.class) return 0d;
        if (t == float.class) return 0f;
        if (t == char.class) return '\0';
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        return 0;
    }
}
