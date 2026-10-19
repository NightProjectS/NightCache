package weeno.nightcache.platform;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.utils.Log;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Abstraction minimale de la plateforme, de Spigot 1.8 à Paper/Folia récents.
 * <ul>
 *   <li>{@link #syncTimer} : tâche répétée sur le thread serveur (Bukkit) ou le thread global (Folia) ;</li>
 *   <li>{@link #asyncTimer} : tâche répétée hors thread serveur, sur un unique thread démon de la lib
 *       (jamais le pool async de Bukkit, qui n'existe pas sur Folia).</li>
 * </ul>
 * Aucune dépendance aux classes NMS / versionnées : seules {@code Bukkit}, {@code Plugin} et la
 * réflexion Folia (si présente) sont utilisées.
 */
public final class Platform {

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");
    private static final @Nullable MethodHandle GLOBAL_SCHEDULER;
    private static final @Nullable MethodHandle RUN_AT_FIXED_RATE;
    private static final @Nullable MethodHandle CANCEL_FOLIA;

    static {
        MethodHandle global = null, fixed = null, cancel = null;
        if (FOLIA) {
            try {
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                Class<?> grs = Class.forName("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler");
                Class<?> task = Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask");
                global = lookup.findStatic(Bukkit.class, "getGlobalRegionScheduler", MethodType.methodType(grs));
                fixed = lookup.findVirtual(grs, "runAtFixedRate",
                        MethodType.methodType(task, Plugin.class, Consumer.class, long.class, long.class));
                cancel = lookup.findVirtual(task, "cancel", MethodType.methodType(
                        Class.forName("io.papermc.paper.threadedregions.scheduler.ScheduledTask$CancelledState")));
            } catch (ReflectiveOperationException | LinkageError e) {
                Log.severe("API Folia détectée mais inutilisable", e);
                global = fixed = cancel = null;
            }
        }
        GLOBAL_SCHEDULER = global;
        RUN_AT_FIXED_RATE = fixed;
        CANCEL_FOLIA = cancel;
    }

    private static volatile @Nullable ScheduledThreadPoolExecutor timers;

    private Platform() {}

    /** @return {@code true} sur Folia (pas de thread principal unique). */
    public static boolean folia() {
        return FOLIA;
    }

    /**
     * Répète {@code task} toutes les {@code periodTicks} sur le thread serveur (thread global sur Folia).
     * Thread: main-thread-only (appel), {@code task} s'exécute sur le thread serveur.
     *
     * @return poignée d'annulation (à appeler au disable).
     */
    @NotNull
    public static Runnable syncTimer(@NotNull Plugin plugin, @NotNull Runnable task, long delayTicks, long periodTicks) {
        long delay = Math.max(1L, delayTicks);
        long period = Math.max(1L, periodTicks);
        if (FOLIA && GLOBAL_SCHEDULER != null && RUN_AT_FIXED_RATE != null && CANCEL_FOLIA != null) {
            try {
                Consumer<Object> body = ignored -> task.run();
                Object scheduled = RUN_AT_FIXED_RATE.invoke(GLOBAL_SCHEDULER.invoke(), plugin, body, delay, period);
                return () -> {
                    try {
                        CANCEL_FOLIA.invoke(scheduled);
                    } catch (Throwable t) {
                        Log.severe("annulation d'une tâche Folia", t);
                    }
                };
            } catch (Throwable t) {
                throw new IllegalStateException("Planification Folia impossible", t);
            }
        }
        int id = Bukkit.getScheduler().runTaskTimer(plugin, task, delay, period).getTaskId();
        return () -> Bukkit.getScheduler().cancelTask(id);
    }

    /**
     * Répète {@code task} sur le thread démon de la lib (un seul pour tout le plugin).
     * Le corps ne doit toucher que de l'état thread-safe, jamais l'API Bukkit.
     * Thread: thread-safe.
     */
    @NotNull
    public static Runnable asyncTimer(@NotNull String name, @NotNull Runnable task, long periodMillis) {
        ScheduledThreadPoolExecutor ex = timers();
        ScheduledFuture<?> f = ex.scheduleWithFixedDelay(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                Log.severe("tâche périodique '" + name + "'", t);
            }
        }, periodMillis, periodMillis, TimeUnit.MILLISECONDS);
        return () -> f.cancel(false);
    }

    private static synchronized ScheduledThreadPoolExecutor timers() {
        ScheduledThreadPoolExecutor ex = timers;
        if (ex == null || ex.isShutdown()) {
            ex = new ScheduledThreadPoolExecutor(1, r -> {
                Thread t = new Thread(r, "NightCache-" + Log.owner() + "-timer");
                t.setDaemon(true);
                t.setContextClassLoader(null); // ne retient pas le classloader du plugin
                return t;
            });
            ex.setRemoveOnCancelPolicy(true); // une tâche annulée ne reste pas dans la file (pas de fuite)
            ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
            timers = ex;
        }
        return ex;
    }

    /** Arrête le thread des timers async. Thread: main-thread-only (disable). */
    public static synchronized void shutdown() {
        ScheduledThreadPoolExecutor ex = timers;
        timers = null;
        if (ex == null) return;
        ex.shutdownNow();
        try {
            if (!ex.awaitTermination(2, TimeUnit.SECONDS)) Log.warning("thread des timers toujours actif après 2 s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.severe("arrêt du thread des timers interrompu", e);
        }
    }

    /** @return {@code true} si l'appel se fait sur le thread serveur principal (toujours faux sur Folia). */
    public static boolean isPrimaryThread() {
        return !FOLIA && Bukkit.isPrimaryThread();
    }

    private static boolean classExists(@NotNull String name) {
        try {
            Class.forName(name, false, Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
