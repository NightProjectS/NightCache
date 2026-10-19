package weeno.nightcache.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.util.Map;

/**
 * Store de persistance disque clé→valeur, indexé, à accès aléatoire.
 * <p>
 * Permet le pattern <b>load-on-join / save-on-quit</b> : seules les entrées
 * réellement utilisées sont chargées en RAM, jamais tout le dataset.
 *
 * @param <K> type de la clé.
 * @param <V> type de la valeur.
 */
public interface PersistentStore<K, V> {

    /** @return nom logique du store (et du fichier {@code .db}). */
    @NotNull
    String name();

    /**
     * Charge une seule entrée par sa clé (O(log n), lecture indexée).
     *
     * @param key clé recherchée.
     * @return la valeur, ou {@code null} si absente du disque.
     */
    @Nullable
    V load(@NotNull K key);

    /**
     * Charge l'intégralité du store en RAM.
     * À réserver aux petits datasets (factions, config) — jamais pour playerData à 10k.
     *
     * @return map immuable de toutes les entrées persistées.
     */
    @NotNull
    @Unmodifiable
    Map<K, V> loadAll();

    /**
     * Écrit (insert ou update) une entrée sur disque.
     *
     * @param key   clé.
     * @param value valeur à persister.
     */
    void save(@NotNull K key, @NotNull V value);

    /**
     * Écrit un lot d'entrées dans une seule transaction (rapide et atomique).
     *
     * @param entries entrées à persister.
     */
    void saveAll(@NotNull Map<K, V> entries);

    /**
     * Supprime une entrée du disque.
     *
     * @param key clé à supprimer.
     */
    void delete(@NotNull K key);

    /** @return nombre d'entrées persistées sur disque. */
    long count();

    /** Ferme la connexion et libère les ressources natives. */
    void close();
}
