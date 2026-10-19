package weeno.nightcache.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weeno.nightcache.interfaces.Serializer;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.utils.KeyCodec;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Store réel sur le pilote SQLite 3.7.2 des serveurs 1.8, via l'écrivain unique de PersistenceManager. */
class SqliteStoreTest {

    /** Valeur "a|b" ; "BOOM" fait échouer la sérialisation, "?" est illisible à la relecture. */
    private static final Serializer<String> TEXT = new Serializer<>() {
        @Override
        public String serialize(String value) {
            if (value.equals("BOOM")) throw new IllegalStateException("sérialisation impossible");
            return value;
        }

        @Override
        public String deserialize(String data) {
            return data.equals("?") ? null : data;
        }
    };

    @TempDir
    Path dir;

    @BeforeEach
    void open() {
        PersistenceManager.initializeWriter(dir.resolve("Data").resolve("Test").toFile(), Logger.getAnonymousLogger());
    }

    @AfterEach
    void close() {
        PersistenceManager.shutdown(5_000);
    }

    private SqliteStore<String, String> store() {
        return PersistenceManager.store("joueurs", KeyCodec.string(), TEXT);
    }

    @Test
    void saveLoadDeleteReuseCachedStatements() {
        SqliteStore<String, String> s = store();
        for (int i = 0; i < 500; i++) s.save("k" + i, "v" + i); // même requête réutilisée 500 fois
        assertEquals(500, s.count());
        assertEquals("v42", s.load("k42"));
        for (int i = 0; i < 250; i++) s.delete("k" + i);
        assertEquals(250, s.count());
        assertNull(s.load("k10"));
        s.save("k499", "nouveau");
        assertEquals("nouveau", s.load("k499"));
    }

    @Test
    void failedBatchIsNeverReplayed() {
        SqliteStore<String, String> s = store();
        Map<String, String> bad = new LinkedHashMap<>();
        bad.put("a", "1");
        bad.put("b", "BOOM"); // échec au milieu du lot
        assertThrows(IllegalStateException.class, () -> s.saveAll(bad));
        assertEquals(0, s.count(), "transaction annulée");
        Map<String, String> good = new LinkedHashMap<>();
        good.put("c", "3");
        s.saveAll(good);
        assertEquals(1, s.count(), "le lot partiel précédent n'a pas été rejoué");
        assertNull(s.load("a"));
        assertEquals("3", s.load("c"));
    }

    @Test
    void replaceAllSwapsContentAtomically() {
        SqliteStore<String, String> s = store();
        s.save("old", "x");
        Map<String, String> rows = new LinkedHashMap<>();
        for (int i = 0; i < 300; i++) rows.put("r" + i, "v" + i);
        s.replaceAll(rows);
        assertEquals(300, s.count());
        assertNull(s.load("old"));
        Map<String, String> failing = Map.of("z", "BOOM");
        assertThrows(IllegalStateException.class, () -> s.replaceAll(failing));
        assertEquals(300, s.count(), "rollback : l'ancien contenu reste intact");
    }

    @Test
    void unreadableRowIsQuarantinedNotLost() {
        SqliteStore<String, String> s = store();
        s.save("ok", "fine");
        s.save("broken", "?");
        Map<String, String> all = s.loadAll();
        assertEquals(Map.of("ok", "fine"), all);
        assertEquals(1, s.count(), "ligne illisible retirée de data");
    }

    @Test
    void maintenanceKeepsStoreUsable() {
        SqliteStore<String, String> s = store();
        for (int i = 0; i < 2_000; i++) s.save("k" + i, "valeur-assez-longue-" + i);
        for (int i = 0; i < 1_900; i++) s.delete("k" + i); // beaucoup de pages libres : VACUUM probable
        SqliteStore.Maintenance m = s.maintain();
        assertTrue(m.healthy());
        assertEquals(100, m.rows());
        s.save("après", "vacuum"); // requêtes en cache recréées après fermeture
        assertEquals("vacuum", s.load("après"));
        assertEquals(101, s.count());
    }
}
