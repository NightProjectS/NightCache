package weeno.nightcache.save;

import org.jetbrains.annotations.NotNull;
import weeno.nightcache.database.SqliteStore;
import weeno.nightcache.utils.Log;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Écriture de snapshots complets d'un manager via {@link SqliteStore#replaceAll(Map)}, avec les
 * deux protections imposées :
 * <ul>
 *   <li>drapeau {@code ready} : tant que le chargement initial n'a pas réussi, aucun
 *       {@code replaceAll} (sinon un démarrage en erreur vide le disque) ;</li>
 *   <li>tickets : un snapshot plus ancien que le dernier écrit est ignoré (une vieille
 *       sauvegarde n'écrase jamais une neuve).</li>
 * </ul>
 * Usage : construire les lignes sur le main thread puis {@link #submit(Map)} ; au
 * {@code onDisable}, {@link #writeNow(Map)}.
 */
public final class SnapshotWriter<K, V> {

    private final SqliteStore<K, V> store;
    private final AtomicLong tickets = new AtomicLong();
    private final AtomicLong lastWritten = new AtomicLong(-1L);
    private volatile boolean ready;

    public SnapshotWriter(@NotNull SqliteStore<K, V> store) {
        this.store = store;
    }

    /** À appeler une fois le {@code loadAll()} initial réussi. Thread: thread-safe. */
    public void markReady() {
        ready = true;
    }

    /** Thread: thread-safe. */
    public boolean isReady() {
        return ready;
    }

    /**
     * Écrit en async un snapshot <b>déjà construit sur le main thread</b> (copie détachée de l'état vivant).
     * Thread: main-thread-only.
     *
     * @return {@code false} si refusé (pas prêt / arrêt en cours / file pleine).
     */
    public boolean submit(@NotNull Map<K, V> rows) {
        if (!ready || PersistenceManager.isStopping()) return false;
        long ticket = tickets.incrementAndGet();
        return PersistenceManager.async(() -> write(ticket, rows));
    }

    /**
     * Écriture synchrone (fenêtre {@code onDisable} uniquement, avant {@code PersistenceManager.shutdown()}).
     * Exécutée sur le writer, l'appelant attend (borné). Thread: main-thread-only.
     */
    public boolean writeNow(@NotNull Map<K, V> rows) {
        if (!ready) {
            Log.warning("snapshot '" + store.name() + "' ignoré : chargement initial non réussi");
            return false;
        }
        long ticket = tickets.incrementAndGet();
        PersistenceManager.onWriter(store.name(), "snapshot", () -> {
            write(ticket, rows);
            return null;
        });
        return true;
    }

    /** Toujours sur le writer (thread unique) : aucun verrou nécessaire. */
    private void write(long ticket, @NotNull Map<K, V> rows) {
        if (ticket <= lastWritten.get()) return; // un snapshot plus récent est déjà écrit
        store.replaceAll(rows);
        lastWritten.set(ticket);
    }
}
