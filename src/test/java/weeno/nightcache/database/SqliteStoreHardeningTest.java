package weeno.nightcache.database;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weeno.nightcache.save.PersistenceManager;
import weeno.nightcache.utils.GsonSerializer;
import weeno.nightcache.utils.JsonRow;
import weeno.nightcache.utils.KeyCodec;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Entrées hostiles et modèle aléatoire sur un vrai store SQLite (les deux pilotes). */
@Tag("sqlite")
class SqliteStoreHardeningTest {

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

    private SqliteStore<String, JsonRow> rows() {
        return PersistenceManager.store("lignes", KeyCodec.string(), JsonRow.SERIALIZER);
    }

    @Test
    void hostileKeysAndValuesRoundTrip() {
        SqliteStore<String, JsonRow> s = rows();
        String[] keys = {"", " ", "'; DROP TABLE data; --", "clé é ü ß 漢字 🎉", "a\u0000b", "\n\t\r", "x".repeat(10_000)};
        for (String k : keys) {
            s.save(k, JsonRow.create().put("v", k).put("n", Long.MIN_VALUE).put("d", Double.NaN).put("b", true));
        }
        for (String k : keys) {
            JsonRow r = s.load(k);
            assertEquals(k, r.getString("v"), "clé " + k.length());
            assertEquals(Long.MIN_VALUE, r.getLong("n", 0));
            assertTrue(Double.isNaN(r.getDouble("d", 0)));
        }
        assertEquals(keys.length, s.count());
        assertEquals(keys.length, s.loadAll().size());
        String big = "é".repeat(1_000_000);
        s.save("gros", JsonRow.create().put("v", big));
        assertEquals(big, s.load("gros").getString("v"), "valeur de 1 Mo relue à l'identique");
    }

    @Test
    void randomOperationsMatchAReferenceMap() {
        SqliteStore<String, JsonRow> s = rows();
        Map<String, Long> model = new HashMap<>();
        Random r = new Random(99);
        for (int op = 0; op < 3_000; op++) {
            String k = "k" + r.nextInt(200);
            switch (r.nextInt(5)) {
                case 0 -> { long v = r.nextLong(); s.save(k, JsonRow.create().put("v", v)); model.put(k, v); }
                case 1 -> { s.delete(k); model.remove(k); }
                case 2 -> {
                    Map<String, JsonRow> batch = new LinkedHashMap<>();
                    for (int i = 0; i < 20; i++) {
                        String bk = "k" + r.nextInt(200);
                        long v = r.nextLong();
                        batch.put(bk, JsonRow.create().put("v", v));
                        model.put(bk, v);
                    }
                    s.saveAll(batch);
                }
                case 3 -> {
                    JsonRow row = s.load(k);
                    Long m = model.get(k);
                    if (m == null) assertNull(row);
                    else assertEquals(m.longValue(), row.l("v"));
                }
                default -> assertEquals(model.size(), s.count());
            }
        }
        Map<String, JsonRow> all = s.loadAll();
        assertEquals(model.size(), all.size());
        model.forEach((k, v) -> assertEquals(v.longValue(), all.get(k).l("v")));
    }

    @Test
    void replaceAllWithNothingEmptiesAndTrimKeepsNewest() {
        SqliteStore<String, JsonRow> s = rows();
        for (int i = 0; i < 50; i++) s.save(String.format("%05d", i), JsonRow.create().put("i", i));
        assertEquals(0, s.trimToNewest(0), "borne invalide : rien supprimé");
        assertEquals(0, s.trimToNewest(-5));
        assertEquals(40, s.trimToNewest(10));
        assertEquals(10, s.count());
        assertTrue(s.load("00049") != null && s.load("00039") == null);
        s.replaceAll(Map.of());
        assertEquals(0, s.count());
        s.saveAll(Map.of());
        assertEquals(0, s.count());
    }

    @Test
    void unserializableValueFailsAloneAndNeverCorruptsTheStore() {
        // Gson refuse NaN dans un objet : l'échec reste confiné à cet appel.
        SqliteStore<UUID, Stats> s = PersistenceManager.store("stats", KeyCodec.uuid(), GsonSerializer.of(Stats.class));
        UUID ok = UUID.randomUUID();
        UUID nan = UUID.randomUUID();
        s.save(ok, new Stats(1.5));
        assertThrows(IllegalStateException.class, () -> s.save(nan, new Stats(Double.NaN)));
        Map<UUID, Stats> batch = new LinkedHashMap<>();
        batch.put(UUID.randomUUID(), new Stats(2));
        batch.put(nan, new Stats(Double.NaN));
        assertThrows(IllegalStateException.class, () -> s.saveAll(batch));
        assertEquals(1, s.count(), "lot annulé en entier");
        assertEquals(1.5, s.load(ok).ratio);
        s.save(nan, new Stats(3));
        assertEquals(2, s.count());
    }

    @Test
    void closedStoreRefusesWithAClearError() {
        SqliteStore<String, JsonRow> s = rows();
        s.save("a", JsonRow.create());
        s.close();
        s.close(); // idempotent
        assertThrows(IllegalStateException.class, () -> s.load("a"));
        assertThrows(IllegalStateException.class, () -> s.save("b", JsonRow.create()));
    }

    @Test
    void maintenanceOnAnEmptyStore() {
        SqliteStore.Maintenance m = rows().maintain();
        assertTrue(m.healthy());
        assertEquals(0, m.rows());
        assertEquals("lignes", m.store());
    }

    /** Valeur simple (gson 2.2.4 des serveurs 1.8 : pas de record). */
    public static final class Stats {
        double ratio;

        Stats(double ratio) {
            this.ratio = ratio;
        }
    }
}
