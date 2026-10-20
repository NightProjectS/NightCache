package weeno.nightcache.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.CacheManager;
import weeno.nightcache.platform.Platform;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.save.PlayerStates;

import java.util.UUID;

/**
 * Join → chargement programmé des données joueur ; quit → purge des états temporaires puis
 * sauvegarde/éviction. Les lots et l'I/O sont gérés par {@code PersistenceManager} (aucune I/O ici).
 * <p>
 * Thread: thread de l'événement. Sur Folia, join/quit arrivent sur le thread de la région du joueur alors que les
 * bindings vivent sur le thread global (tick de persistance) : chargement et sauvegarde y sont donc transférés, dans
 * l'ordre, plutôt que de modifier leurs collections en concurrence. Sur Bukkit, tout reste synchrone.
 */
public final class DataListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        global(() -> PersistenceManager.loadPlayer(id));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        PlayerStates.evict(id); // hooks métier du plugin : thread du joueur (région Folia)
        global(() -> PersistenceManager.savePlayer(id));
    }

    private static void global(@NotNull Runnable task) {
        Plugin plugin = CacheManager.plugin();
        if (plugin == null || !Platform.folia()) task.run();
        else Platform.runGlobal(plugin, task);
    }
}
