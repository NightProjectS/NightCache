package weeno.nightcache.interfaces;

import org.jetbrains.annotations.NotNull;

/**
 * Contrat d'un pool d'objets réutilisables pour éliminer les allocations répétées
 * sur les chemins chauds (réduction de la pression GC à 10k joueurs).
 *
 * @param <T> type de l'objet poolé.
 */
public interface ObjectPool<T> {

    /**
     * Emprunte un objet (recyclé et remis à zéro, ou neuf si le pool est vide).
     * L'appelant doit appeler {@link #release(Object)} après usage.
     */
    @NotNull
    T borrow();

    /**
     * Restitue un objet au pool. Si le pool est plein, l'objet est abandonné (pas de fuite).
     *
     * @param obj objet à restituer.
     */
    void release(@NotNull T obj);

    /** @return nombre d'objets disponibles dans le pool. */
    int available();

    /** @return capacité maximale du pool. */
    int capacity();

    /** Vide le pool — appelé au {@code onDisable}. */
    void clear();
}
