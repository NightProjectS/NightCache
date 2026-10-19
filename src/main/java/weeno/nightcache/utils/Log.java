package weeno.nightcache.utils;

import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Journalisation interne de la lib : logger du plugin hôte s'il est connu, sinon logger
 * {@code NightCache}. Aucun {@code catch} de la lib ne doit être silencieux.
 * <p>
 * Thread: thread-safe.
 */
public final class Log {

    private static final Logger FALLBACK = Logger.getLogger("NightCache");
    private static volatile Plugin plugin;
    private static volatile Logger logger;

    private Log() {}

    /** Lie la journalisation au plugin hôte ({@code null} = retour au logger par défaut). */
    public static void bind(@Nullable Plugin pl) {
        plugin = pl;
        logger = pl != null ? pl.getLogger() : null;
    }

    /** Variante sans Bukkit (tests). */
    public static void bindLogger(@Nullable Logger log) {
        logger = log;
    }

    /** @return le nom du plugin lié, ou {@code null}. */
    @Nullable
    public static String ownerOrNull() {
        Plugin p = plugin;
        return p != null ? p.getName() : null;
    }

    /** @return nom du plugin hôte (pour nommer les threads), ou {@code "?"}. */
    @NotNull
    public static String owner() {
        Plugin p = plugin;
        return p != null ? p.getName() : "?";
    }

    @NotNull
    public static Logger logger() {
        Logger l = logger;
        return l != null ? l : FALLBACK;
    }

    public static void info(@NotNull String msg) {
        logger().info("[Data] " + msg);
    }

    public static void warning(@NotNull String msg) {
        logger().warning("[Data] " + msg);
    }

    public static void severe(@NotNull String context, @NotNull Throwable t) {
        logger().log(Level.SEVERE, "[Data] " + context, t);
    }
}
