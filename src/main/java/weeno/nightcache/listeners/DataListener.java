package weeno.nightcache.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.save.PlayerStates;

import java.util.UUID;

/**
 * Join → chargement programmé des données joueur ; quit → purge des états temporaires puis
 * sauvegarde/éviction. Les lots et l'I/O sont gérés par {@code PersistenceManager} (aucune I/O ici).
 * Thread: main-thread-only.
 */
public final class DataListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(@NotNull PlayerJoinEvent e) {
        PersistenceManager.loadPlayer(e.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(@NotNull PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        PlayerStates.evict(id);
        PersistenceManager.savePlayer(id);
    }
}
