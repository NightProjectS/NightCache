package weeno.nightcache.save;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.utils.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Registre des états joueur <b>temporaires</b> en RAM (cooldowns, sessions, menus…).
 * <ul>
 *   <li>{@link #track(Map)} / {@link #track(Set)} : purgés au quit et par le balayage périodique ;</li>
 *   <li>{@link #trackRetained(Map)} : survivent au quit (mise en cours…), libérés par leur domaine,
 *       comptés dans le diagnostic et vidés au shutdown ;</li>
 *   <li>{@link #onQuit(Consumer)} : nettoyage métier (rembourser, fermer…) avant la purge.</li>
 * </ul>
 * Un module désactivé à chaud doit appeler {@link #untrack(Object)} / {@link #removeQuitHook(Consumer)}
 * pour ne pas retenir ses structures (fuite). Tout est vidé au {@code onDisable}.
 * <p>
 * Thread: main-thread-only (les maps enregistrées sont manipulées sur le thread serveur).
 */
public final class PlayerStates {

    private static final List<Map<UUID, ?>> MAPS = new CopyOnWriteArrayList<>();
    private static final List<Map<UUID, ?>> RETAINED = new CopyOnWriteArrayList<>();
    private static final List<Set<UUID>> SETS = new CopyOnWriteArrayList<>();
    private static final List<Consumer<UUID>> HOOKS = new CopyOnWriteArrayList<>();

    private PlayerStates() {}

    /** Compatibilité : la journalisation suit le plugin lié par {@code NightCache.enable}. */
    public static void init(@NotNull Logger log) {
        if (Log.ownerOrNull() == null) Log.bindLogger(log);
    }

    @NotNull
    public static <V, M extends Map<UUID, V>> M track(@NotNull M map) {
        if (!containsIdentity(MAPS, map)) MAPS.add(map);
        return map;
    }

    @NotNull
    public static <V, M extends Map<UUID, V>> M trackRetained(@NotNull M map) {
        if (!containsIdentity(RETAINED, map)) RETAINED.add(map);
        return map;
    }

    @NotNull
    public static <S extends Set<UUID>> S track(@NotNull S set) {
        if (!containsIdentity(SETS, set)) SETS.add(set);
        return set;
    }

    public static void onQuit(@NotNull Consumer<UUID> hook) {
        if (!containsIdentity(HOOKS, hook)) HOOKS.add(hook);
    }

    /** Désenregistre une map ou un set (module arrêté à chaud). */
    public static boolean untrack(@NotNull Object structure) {
        boolean removed = MAPS.removeIf(m -> m == structure);
        removed |= RETAINED.removeIf(m -> m == structure);
        removed |= SETS.removeIf(s -> s == structure);
        return removed;
    }

    public static boolean removeQuitHook(@NotNull Consumer<UUID> hook) {
        return HOOKS.removeIf(h -> h == hook);
    }

    /** Purge le joueur : hooks métier d'abord, puis maps et sets suivis. */
    public static void evict(@NotNull UUID id) {
        for (Consumer<UUID> hook : HOOKS) {
            try {
                hook.accept(id);
            } catch (Exception e) {
                Log.severe("nettoyage du joueur " + id, e);
            }
        }
        for (Map<UUID, ?> map : MAPS) {
            try {
                map.remove(id);
            } catch (Exception e) {
                Log.severe("purge de l'état joueur " + id, e);
            }
        }
        for (Set<UUID> set : SETS) {
            try {
                set.remove(id);
            } catch (Exception e) {
                Log.severe("purge de l'état joueur " + id, e);
            }
        }
    }

    /**
     * Filet de sécurité (quit manqué) : purge les UUID qui ne sont plus en ligne.
     * @return nombre de joueurs purgés.
     */
    public static int sweep() {
        if (nothingTracked()) return 0; // cas courant : aucune allocation (appelé toutes les 600 ticks)
        Set<UUID> ids = new HashSet<>();
        for (Map<UUID, ?> map : MAPS) ids.addAll(map.keySet());
        for (Set<UUID> set : SETS) ids.addAll(set);
        int removed = 0;
        for (UUID id : ids) {
            if (id == null || Bukkit.getPlayer(id) == null) {
                if (id != null) evict(id);
                else {
                    for (Map<UUID, ?> map : MAPS) map.remove(null);
                    for (Set<UUID> set : SETS) set.remove(null);
                }
                removed++;
            }
        }
        return removed;
    }

    /** Boucles indexées : pas d'itérateur alloué sur les listes copy-on-write. */
    private static boolean nothingTracked() {
        for (int i = 0; i < MAPS.size(); i++) if (!MAPS.get(i).isEmpty()) return false;
        for (int i = 0; i < SETS.size(); i++) if (!SETS.get(i).isEmpty()) return false;
        return true;
    }

    public static int trackedMaps() {
        return MAPS.size() + RETAINED.size() + SETS.size();
    }

    public static int trackedEntries() {
        int n = 0;
        for (Map<UUID, ?> m : MAPS) n += m.size();
        for (Map<UUID, ?> m : RETAINED) n += m.size();
        for (Set<UUID> s : SETS) n += s.size();
        return n;
    }

    /** Vide et désenregistre tout (après les sauvegardes finales). */
    public static void shutdown() {
        for (Map<UUID, ?> m : MAPS) clear(m);
        for (Map<UUID, ?> m : RETAINED) clear(m);
        for (Set<UUID> s : SETS) {
            try {
                s.clear();
            } catch (Exception e) {
                Log.severe("vidage des états joueur", e);
            }
        }
        MAPS.clear();
        RETAINED.clear();
        SETS.clear();
        HOOKS.clear();
    }

    private static void clear(@NotNull Map<UUID, ?> m) {
        try {
            m.clear();
        } catch (Exception e) {
            Log.severe("vidage des états joueur", e);
        }
    }

    private static boolean containsIdentity(@NotNull List<?> list, @NotNull Object o) {
        for (Object e : list) if (e == o) return true;
        return false;
    }
}
