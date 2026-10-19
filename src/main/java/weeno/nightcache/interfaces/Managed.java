package weeno.nightcache.interfaces;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import weeno.nightcache.objects.CacheStats;

public interface Managed {

    /** @return nom unique de ce cache dans le registre central. */
    @NotNull
    @Contract(pure = true)
    String name();

    /** @return taille approximative (non bloquant, peut être stale d'un tick). */
    @Contract(pure = true)
    long estimatedSize();

    /** Vide toutes les entrées sans attendre le janitor. */
    void invalidateAll();

    /** Force l'éviction des entrées expirées — appelé par le janitor async toutes les 30 s. */
    void cleanUp();

    /** @return snapshot des statistiques (hits, miss, évictions, taille). */
    @NotNull
    @Contract(pure = true)
    CacheStats stats();
}
