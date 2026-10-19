package weeno.nightcache.interfaces;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

public interface ManagedCache<K, V> extends Managed {

    /**
     * Retourne la valeur associée à {@code key}, ou {@code null} si absente/expirée.
     * N'appelle jamais de loader — lecture pure, O(1).
     */
    @Nullable
    V getIfPresent(@NotNull K key);

    /**
     * Retourne la valeur associée à {@code key}.
     * Si absente ou expirée, appelle {@code loader}, stocke et retourne le résultat.
     *
     * @param loader calculateur appelé au plus une fois par clé manquante.
     */
    @NotNull
    V get(@NotNull K key, @NotNull Function<K, V> loader);

    /**
     * Insère ou remplace la valeur associée à {@code key}.
     * Thread-safe, O(1) amorti.
     */
    void put(@NotNull K key, @NotNull V value);

    /**
     * Supprime immédiatement l'entrée associée à {@code key}.
     * No-op si la clé est absente.
     */
    void invalidate(@NotNull K key);

    /**
     * Vue directe sur la map sous-jacente (thread-safe, pas de copie).
     * Utile pour les itérations bulk.
     */
    @NotNull
    ConcurrentMap<K, V> asMap();
}
