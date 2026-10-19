package weeno.nightcache.database;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import weeno.nightcache.interfaces.PersistentStore;
import weeno.nightcache.interfaces.Serializer;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.utils.KeyCodec;
import weeno.nightcache.utils.Log;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Store clé/valeur sur un fichier SQLite ({@code Data/<Plugin>/<name>.db}), table
 * {@code data(k TEXT PRIMARY KEY, v TEXT, t INTEGER)}, valeurs JSON, mode WAL.
 * <p>
 * <b>Écrivain unique</b> : la connexion n'est utilisée que par le thread I/O de
 * {@link PersistenceManager}. Appelée depuis un autre thread, chaque opération y est
 * <b>redirigée</b> et attendue (fenêtres bornées : chargement à l'enable, sauvegarde du disable) ;
 * un appel bloquant sur le main thread en régime normal est signalé une fois dans les logs.
 * <p>
 * Lignes illisibles : jamais de crash ni d'écrasement silencieux — la ligne est déplacée dans la
 * table {@code quarantine} puis ignorée ({@code null}).
 * <p>
 * Construction sans I/O : la connexion s'ouvre paresseusement sur le writer.
 */
public final class SqliteStore<K, V> implements PersistentStore<K, V> {

    private static final String UPSERT = "INSERT OR REPLACE INTO data (k, v, t) VALUES (?, ?, ?)";
    private static final long VACUUM_PERCENT = 25L;

    private final File file;
    private final String name;
    private final KeyCodec<K> codec;
    private final Serializer<V> serializer;

    private @Nullable Connection connection; // confiné au writer
    private boolean closed;

    /** Ne pas instancier directement : passer par {@code PersistenceManager.store(...)}. */
    @ApiStatus.Internal
    public SqliteStore(@NotNull File file, @NotNull String name,
                       @NotNull KeyCodec<K> codec, @NotNull Serializer<V> serializer) {
        this.file = file;
        this.name = name;
        this.codec = codec;
        this.serializer = serializer;
    }

    /** Thread: thread-safe. */
    @Override @NotNull
    public String name() { return name; }

    /** Ouvre le fichier et le schéma (appelé par le writer juste après la création). */
    public void initialize() {
        PersistenceManager.onWriter(name, "initialize", () -> { connection(); return null; });
    }

    /** @return la valeur, ou {@code null} si absente ou illisible (mise en quarantaine). */
    @Override @Nullable
    public V load(@NotNull K key) {
        return PersistenceManager.onWriter(name, "load", () -> {
            String k = codec.encode(key);
            try (PreparedStatement ps = prepare("SELECT v FROM data WHERE k=?")) {
                ps.setString(1, k);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    String raw = rs.getString(1);
                    V v = decode(raw);
                    if (v == null) quarantine(k, raw);
                    return v;
                }
            }
        });
    }

    /** Lecture complète (lignes illisibles mises en quarantaine et ignorées). */
    @Override @NotNull @Unmodifiable
    public Map<K, V> loadAll() {
        return PersistenceManager.onWriter(name, "loadAll", () -> {
            Map<K, V> out = new HashMap<>();
            Map<String, String> bad = new HashMap<>();
            try (PreparedStatement ps = prepare("SELECT k, v FROM data"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String k = rs.getString(1);
                    String raw = rs.getString(2);
                    K key = decodeKey(k);
                    V v = key != null ? decode(raw) : null;
                    if (v != null) out.put(key, v);
                    else bad.put(k, raw);
                }
            }
            bad.forEach(this::quarantine);
            return Collections.unmodifiableMap(out);
        });
    }

    @Override
    public void save(@NotNull K key, @NotNull V value) {
        saveAll(Collections.singletonMap(key, value));
    }

    /** Upsert en une transaction. Les valeurs doivent être détachées de l'état vivant. */
    @Override
    public void saveAll(@NotNull Map<K, V> entries) {
        if (entries.isEmpty()) return;
        PersistenceManager.onWriter(name, "saveAll", () -> transaction(false, entries));
    }

    /**
     * Remplace <b>tout</b> le contenu par {@code entries} en une transaction (échec → rollback,
     * l'ancien contenu reste intact). Ne jamais l'appeler avant un chargement initial réussi :
     * voir {@code SnapshotWriter}.
     */
    public void replaceAll(@NotNull Map<K, V> entries) {
        PersistenceManager.onWriter(name, "replaceAll", () -> transaction(true, entries));
    }

    @Override
    public void delete(@NotNull K key) {
        PersistenceManager.onWriter(name, "delete", () -> {
            try (PreparedStatement ps = prepare("DELETE FROM data WHERE k=?")) {
                ps.setString(1, codec.encode(key));
                ps.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Ne garde que les {@code keep} lignes de plus grande clé (clés triables : horodatage, séquence).
     * Purge fondée sur une règle métier explicite, jamais sur la colonne {@code t}.
     * @return lignes supprimées.
     */
    public long trimToNewest(long keep) {
        if (keep <= 0) return 0L;
        return PersistenceManager.onWriter(name, "trimToNewest", () -> {
            try (PreparedStatement ps = prepare(
                    "DELETE FROM data WHERE k NOT IN (SELECT k FROM data ORDER BY k DESC LIMIT ?)")) {
                ps.setLong(1, keep);
                return (long) ps.executeUpdate();
            }
        });
    }

    @Override
    public long count() {
        return PersistenceManager.onWriter(name, "count", () -> {
            try (PreparedStatement ps = prepare("SELECT COUNT(*) FROM data"); ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    /**
     * Entretien : {@code quick_check} → {@code wal_checkpoint(TRUNCATE)} → {@code ANALYZE} →
     * {@code VACUUM} seulement si les pages libres dépassent 25 %. Chaque étape est tolérante
     * (vieux SQLite du serveur 1.8 : certaines pragmas n'existent pas).
     */
    @NotNull
    public Maintenance maintain() {
        return PersistenceManager.onWriter(name, "maintain", () -> {
            Connection c = connection();
            try (Statement st = c.createStatement()) {
                boolean healthy = true;
                try (ResultSet rs = st.executeQuery("PRAGMA quick_check")) {
                    healthy = rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
                } catch (SQLException e) {
                    Log.warning("store '" + name + "' : quick_check indisponible (" + e.getMessage() + ")");
                }
                try {
                    st.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                } catch (SQLException e) {
                    st.execute("PRAGMA wal_checkpoint"); // SQLite < 3.8.8
                }
                st.execute("ANALYZE");
                long pages = scalar(st, "PRAGMA page_count");
                long free = scalar(st, "PRAGMA freelist_count");
                boolean compacted = healthy && pages > 0 && free * 100L / pages >= VACUUM_PERCENT;
                if (compacted) st.execute("VACUUM");
                return new Maintenance(name, scalar(st, "SELECT COUNT(*) FROM data"), healthy, compacted, pages, free);
            }
        });
    }

    /** Bilan d'un {@link #maintain()}. */
    public record Maintenance(@NotNull String store, long rows, boolean healthy, boolean compacted,
                              long pages, long freePages) {}

    /** Ferme la connexion (writer). Idempotent. */
    @Override
    public void close() {
        PersistenceManager.onWriter(name, "close", () -> {
            closed = true;
            Connection c = connection;
            connection = null;
            if (c != null) c.close();
            return null;
        });
    }

    // ─── interne : toujours exécuté sur le writer ───────────────────────────

    private @NotNull Connection connection() throws SQLException {
        if (closed) throw new SQLException("store fermé : " + name);
        Connection c = connection;
        if (c != null) return c;
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new SQLException("dossier inaccessible : " + dir);
        c = SqliteConnections.open(file);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=3000");
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
            st.execute("CREATE TABLE IF NOT EXISTS data (k TEXT PRIMARY KEY, v TEXT NOT NULL, t INTEGER NOT NULL)");
        } catch (SQLException e) {
            try { c.close(); } catch (SQLException close) { e.addSuppressed(close); }
            throw e;
        }
        connection = c;
        return c;
    }

    private @NotNull PreparedStatement prepare(@NotNull String sql) throws SQLException {
        return connection().prepareStatement(sql);
    }

    private Void transaction(boolean replace, @NotNull Map<K, V> entries) throws SQLException {
        Connection c = connection();
        c.setAutoCommit(false);
        try {
            if (replace) {
                try (Statement st = c.createStatement()) { st.executeUpdate("DELETE FROM data"); }
            }
            if (!entries.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<K, V> e : entries.entrySet()) {
                        ps.setString(1, codec.encode(e.getKey()));
                        ps.setString(2, serializer.serialize(e.getValue()));
                        ps.setLong(3, now);
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            c.commit();
            return null;
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException r) { e.addSuppressed(r); }
            throw e;
        } finally {
            c.setAutoCommit(true);
        }
    }

    private @Nullable V decode(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return serializer.deserialize(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private @Nullable K decodeKey(@Nullable String raw) {
        if (raw == null) return null;
        try {
            return codec.decode(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Déplace une ligne illisible dans {@code quarantine} (jamais détruite, jamais écrasée en silence). */
    private void quarantine(@NotNull String key, @Nullable String raw) {
        try (Statement st = connection().createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS quarantine (k TEXT NOT NULL, v TEXT, t INTEGER NOT NULL)");
        } catch (SQLException e) {
            Log.severe("store '" + name + "' : création de la table quarantine", e);
            return;
        }
        try (PreparedStatement ps = prepare("INSERT INTO quarantine (k, v, t) VALUES (?, ?, ?)")) {
            ps.setString(1, key);
            ps.setString(2, raw);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) {
            Log.severe("store '" + name + "' : mise en quarantaine de '" + key + "'", e);
            return; // la ligne reste dans data : rien n'est perdu
        }
        try (PreparedStatement ps = prepare("DELETE FROM data WHERE k=?")) {
            ps.setString(1, key);
            ps.executeUpdate();
            Log.warning("store '" + name + "' : ligne illisible '" + key + "' déplacée en quarantaine");
        } catch (SQLException e) {
            Log.severe("store '" + name + "' : mise en quarantaine de '" + key + "'", e);
        }
    }

    private static long scalar(@NotNull Statement st, @NotNull String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }
}
