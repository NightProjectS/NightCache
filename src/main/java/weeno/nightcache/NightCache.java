package weeno.nightcache;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.diag.DiagSnapshot;
import weeno.nightcache.listeners.DataListener;
import weeno.nightcache.platform.Platform;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.save.PlayerStates;
import weeno.nightcache.utils.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>NightCache</b> — caches RAM et persistance SQLite locale pour plugins Bukkit/Spigot/Paper
 * (1.8 → récents, Folia compris), Java 21.
 * <pre>{@code
 * public void onEnable() {
 *     NightCache.enable(this);             // tout début : Data/<Plugin>/, caches, join/quit
 *     ... migration héritée, stores + loadAll(), managers ...
 *     PersistenceManager.maintenance();    // tout à la fin, une seule fois
 * }
 *
 * public void onDisable() {
 *     shuttingDown = true;                 // 1. drapeau
 *     PersistenceManager.beginShutdown();  //    plus aucune entrée de persistance
 *     Bukkit.getScheduler().cancelTasks(this); // 2. tâches
 *     ... 3. arrêt des modules, 4. sauvegarde SYNCHRONE des managers ...
 *     NightCache.disable();                // 5. PersistenceManager.shutdown()  6. vidage RAM
 * }
 * }</pre>
 * Garanties « zéro fuite » : aucun thread tant qu'aucun store/timer n'est utilisé, threads
 * démon sans classloader de contexte, toutes les structures statiques vidées au disable,
 * pilote JDBC isolé désenregistré et son classloader fermé.
 * <p>
 * La lib doit être <b>shadée et relocalisée</b> dans chaque plugin (état statique par plugin).
 */
public final class NightCache {

    private static volatile boolean listening;

    private NightCache() {}

    /**
     * Initialise persistance (paresseuse), caches et cycle join/quit, et programme le chargement
     * des joueurs déjà en ligne (reload). Idempotent. Thread: main-thread-only.
     */
    public static void enable(@NotNull Plugin plugin) {
        Log.bind(plugin);
        PersistenceManager.init(plugin);
        CacheManager.init(plugin);
        if (!listening) {
            Bukkit.getPluginManager().registerEvents(new DataListener(), plugin);
            listening = true;
        }
    }

    /**
     * À appeler après la création des {@code bindPlayer} : programme le chargement des joueurs
     * déjà connectés (cas d'un reload). Thread: main-thread-only.
     */
    public static void loadOnlinePlayers() {
        List<UUID> ids = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) ids.add(p.getUniqueId());
        PersistenceManager.loadPlayers(ids);
    }

    /**
     * Ferme la persistance <b>en dernier</b> (sauvegarde finale + fermeture sur le writer), puis
     * vide caches, états joueur, jauges et threads de la lib. Thread: main-thread-only.
     */
    public static void disable() {
        // Chaque étape s'exécute même si une précédente échoue : aucune structure statique ni thread ne doit survivre.
        step("persistance", PersistenceManager::shutdown);
        step("caches", CacheManager::shutdown);
        step("états joueur", PlayerStates::shutdown);
        step("jauges", DiagSnapshot::clearGauges);
        step("timers", Platform::shutdown);
        listening = false; // le listener est désenregistré par Bukkit avec le plugin
        Log.bind(null);
    }

    private static void step(@NotNull String what, @NotNull Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            Log.severe("arrêt (" + what + ")", e);
        }
    }
}
