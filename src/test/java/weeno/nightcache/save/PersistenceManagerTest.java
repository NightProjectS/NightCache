package weeno.nightcache.save;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weeno.nightcache.caches.CaffeineCache;
import weeno.nightcache.database.SqliteConnections;
import weeno.nightcache.database.SqliteStore;
import weeno.nightcache.interfaces.ManagedCache;
import weeno.nightcache.objects.CacheSpec;
import weeno.nightcache.testsupport.Alloc;
import weeno.nightcache.testsupport.Threads;
import weeno.nightcache.utils.JsonRow;
import weeno.nightcache.utils.KeyCodec;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writer unique, arrêt borné, file bornée et format disque, sur un vrai fichier SQLite. */
@Tag("sqlite")
class PersistenceManagerTest {

    private static final Logger LOG = Logger.getLogger("PersistenceManagerTest");

    @TempDir
    Path dir;

    File data() {
        return dir.resolve("Data").resolve("Test").toFile();
    }

    @BeforeEach
    void open() {
        PersistenceManager.initializeWriter(data(), LOG);
    }

    @AfterEach
    void close() {
        PersistenceManager.shutdown(5_000);
    }

    SqliteStore<String, String> text(String name) {
        return PersistenceManager.store(name, KeyCodec.string(), new weeno.nightcache.interfaces.Serializer<>() {
            @Override public String serialize(String value) { return value; }
            @Override public String deserialize(String data) { return data; }
        });
    }

    // ─── chemins chauds ────────────────────────────────────────────────────

    @Test
    void idleTickAllocatesNothing() {
        // Toutes les 600 ticks, le balayage des états joueur allouait un HashSet même sans aucun état suivi.
        long none = Alloc.bytes(20_000, 100_000, PersistenceManager::tick);
        assertTrue(none <= 512, "tick sans binding : " + none + " octets");
        ManagedCache<UUID, String> cache = new CaffeineCache<>(CacheSpec.persistent("idle"));
        PersistenceManager.bindPlayer(cache, new PlayerBindingTest.MemoryStore(), null);
        long idle = Alloc.bytes(60_000, 100_000, PersistenceManager::tick);
        assertTrue(idle <= 512, "tick avec un binding au repos : " + idle + " octets");
    }

    // ─── arrêt ─────────────────────────────────────────────────────────────

    @Test
    void shutdownIsBoundedWhenAHookHangs() throws Exception {
        text("lent").save("a", "1");
        PersistenceManager.onAutoSave(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        long t0 = System.nanoTime();
        PersistenceManager.shutdown(300);
        long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        assertTrue(ms < 3_000, "arrêt non borné : " + ms + " ms");
        assertTrue(Threads.awaitNone(3_000).isEmpty(), "writer interrompu puis terminé");
    }

    @Test
    void callsDuringTheFinalSaveWaitForTheWriterInsteadOfRacingIt() throws Exception {
        // Pendant la sauvegarde finale (executor arrêté mais writer encore actif), un appel depuis un autre thread
        // s'exécutait directement, en parallèle du writer, sur la même connexion et les mêmes requêtes en cache.
        text("course").save("a", "1");
        CountDownLatch inFinal = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean hookDone = new AtomicBoolean();
        PersistenceManager.onAutoSave(() -> {
            inFinal.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            hookDone.set(true);
        });
        Thread stopper = new Thread(() -> PersistenceManager.shutdown(10_000), "stopper");
        stopper.start();
        assertTrue(inFinal.await(5, TimeUnit.SECONDS));
        ThreadPoolExecutor io = io();
        long end = System.currentTimeMillis() + 5_000;
        while (io != null && !io.isShutdown() && System.currentTimeMillis() < end) Thread.sleep(1);
        AtomicReference<Boolean> seen = new AtomicReference<>();
        Thread probe = new Thread(() -> seen.set(PersistenceManager.onWriter("course", "probe", hookDone::get)), "probe");
        probe.start();
        Thread.sleep(200);
        release.countDown();
        probe.join(10_000);
        stopper.join(10_000);
        assertEquals(Boolean.TRUE, seen.get(), "appel exécuté pendant que le writer travaillait encore");
    }

    @Test
    void nothingSurvivesShutdown() throws Exception {
        SqliteStore<String, String> s = text("vie");
        s.save("a", "1");
        ManagedCache<UUID, String> cache = new CaffeineCache<>(CacheSpec.persistent("vie"));
        PersistenceManager.bindPlayer(cache, PersistenceManager.store("joueurs", KeyCodec.uuid(), JsonRowText.INSTANCE), null);
        PersistenceManager.onAutoSave(() -> {});
        assertTrue(PersistenceManager.async(() -> s.save("b", "2")));
        PersistenceManager.shutdown(5_000);
        assertTrue(Threads.awaitNone(3_000).isEmpty(), "threads NightCache encore vivants : " + Threads.nightCache());
        assertEquals(0, PersistenceManager.storeCount());
        assertEquals(0, PersistenceManager.bindingCount());
        assertEquals(0, PersistenceManager.hookCount());
        assertEquals(0, PersistenceManager.queueSize());
        assertNull(PersistenceManager.folder());
        assertTrue(PersistenceManager.isStopping());
        assertFalse(PersistenceManager.isAccepting());
        assertEquals(List.of(), ownDrivers(), "pilote JDBC de la lib encore enregistré dans DriverManager");
        assertNull(staticField(SqliteConnections.class, "isolated"));
        assertThrows(IllegalStateException.class, () -> text("après"));
        assertFalse(PersistenceManager.async(() -> {}), "écriture refusée une fois fermé");

        // Réactivation (reload) : le pilote désenregistré ne doit pas empêcher de rouvrir les stores.
        PersistenceManager.initializeWriter(data(), LOG);
        assertEquals("1", text("vie").load("a"));
        assertEquals("2", text("vie").load("b"));
    }

    // ─── écritures ─────────────────────────────────────────────────────────

    @Test
    void concurrentAsyncWritesAreAllPersisted() throws Exception {
        SqliteStore<String, String> s = text("concurrence");
        AtomicInteger refused = new AtomicInteger();
        weeno.nightcache.testsupport.Concurrency.run(8, t -> {
            for (int i = 0; i < 400; i++) {
                String k = t + ":" + i;
                if (!PersistenceManager.async(() -> s.save(k, "v" + k))) refused.incrementAndGet();
                if (i % 50 == 0) Thread.yield();
            }
        });
        PersistenceManager.shutdown(10_000);
        PersistenceManager.initializeWriter(data(), LOG);
        assertEquals(3_200 - refused.get(), text("concurrence").count());
        assertEquals(0, refused.get());
    }

    @Test
    void fullQueueRefusesInsteadOfGrowing() throws Exception {
        text("file").save("a", "1");
        CountDownLatch block = new CountDownLatch(1);
        assertTrue(PersistenceManager.async(() -> {
            try {
                block.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        int accepted = 0;
        for (int i = 0; i < PersistenceManager.MAX_QUEUE + 500; i++) if (PersistenceManager.async(() -> {})) accepted++;
        assertTrue(PersistenceManager.queueSize() <= PersistenceManager.MAX_QUEUE);
        assertTrue(accepted <= PersistenceManager.MAX_QUEUE + 1 && accepted >= PersistenceManager.MAX_QUEUE - 1,
                "acceptées : " + accepted);
        block.countDown();
    }

    @Test
    void snapshotWriterNeverLetsAnOldSnapshotWin() {
        SqliteStore<String, String> s = text("snap");
        SnapshotWriter<String, String> w = new SnapshotWriter<>(s);
        assertFalse(w.submit(Map.of("a", "1")), "pas prêt : rien n'est écrit");
        assertFalse(w.writeNow(Map.of("a", "1")));
        assertEquals(0, s.count());
        w.markReady();
        CountDownLatch block = new CountDownLatch(1);
        PersistenceManager.async(() -> {
            try {
                block.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(w.submit(Map.of("v", "ancien")));
        assertTrue(w.submit(Map.of("v", "récent")));
        block.countDown();
        assertEquals("récent", s.load("v"));
        Map<String, String> big = new HashMap<>();
        for (int i = 0; i < 1_000; i++) big.put("k" + i, "v" + i);
        assertTrue(w.writeNow(big));
        assertEquals(1_000, s.count());
        assertNull(s.load("v"), "snapshot complet : remplace tout");
    }

    // ─── format disque (contrat avec les données existantes) ───────────────

    @Test
    void diskFormatIsFrozen() throws Exception {
        SqliteStore<String, JsonRow> s = PersistenceManager.store("format", KeyCodec.string(), JsonRow.SERIALIZER);
        s.save("joueur", JsonRow.create().put("n", "Heeno").put("l", 5L).put("b", true));
        PersistenceManager.shutdown(5_000);
        File db = new File(data(), "format.db");
        assertTrue(db.isFile(), "fichier Data/<Plugin>/<store>.db");
        try (Connection c = rawConnection(db); Statement st = c.createStatement()) {
            List<String> cols = new ArrayList<>();
            try (ResultSet rs = st.executeQuery("PRAGMA table_info(data)")) {
                while (rs.next()) cols.add(rs.getString(2) + " " + rs.getString(3) + " " + rs.getInt(4) + " " + rs.getInt(6));
            }
            assertEquals(List.of("k TEXT 0 1", "v TEXT 1 0", "t INTEGER 1 0"), cols);
            try (ResultSet rs = st.executeQuery("SELECT k, v, t FROM data")) {
                assertTrue(rs.next());
                assertEquals("joueur", rs.getString(1));
                assertEquals("{\"n\":\"Heeno\",\"l\":5,\"b\":true}", rs.getString(2));
                assertTrue(rs.getLong(3) > 1_600_000_000_000L, "t = horodatage en ms");
            }
            st.executeUpdate("INSERT INTO data (k, v, t) VALUES ('casse', 'pas du json', 1)");
        }
        PersistenceManager.initializeWriter(data(), LOG);
        SqliteStore<String, JsonRow> again = PersistenceManager.store("format", KeyCodec.string(), JsonRow.SERIALIZER);
        assertNull(again.load("casse"), "ligne illisible ignorée");
        assertEquals("Heeno", again.load("joueur").getString("n"));
        PersistenceManager.shutdown(5_000);
        try (Connection c = rawConnection(db); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT k, v FROM quarantine")) {
            assertTrue(rs.next(), "ligne déplacée en quarantaine, jamais détruite");
            assertEquals("casse", rs.getString(1));
            assertEquals("pas du json", rs.getString(2));
        }
        PersistenceManager.initializeWriter(data(), LOG);
    }

    // ─── utilitaires ───────────────────────────────────────────────────────

    /** Sérialiseur JsonRow vu comme Serializer&lt;String&gt; pour des valeurs de test simples. */
    enum JsonRowText implements weeno.nightcache.interfaces.Serializer<String> {
        INSTANCE;
        @Override public String serialize(String value) { return JsonRow.create().put("v", value).toString(); }
        @Override public String deserialize(String data) {
            JsonRow r = JsonRow.parse(data);
            return r == null ? null : r.getString("v");
        }
    }

    static Connection rawConnection(File db) throws Exception {
        Driver d = (Driver) Class.forName("org.sqlite.JDBC").getDeclaredConstructor().newInstance();
        return d.connect("jdbc:sqlite:" + db.getAbsolutePath(), new Properties());
    }

    static List<String> ownDrivers() {
        List<String> out = new ArrayList<>();
        Enumeration<Driver> all = DriverManager.getDrivers();
        while (all.hasMoreElements()) {
            Driver d = all.nextElement();
            if (d.getClass().getClassLoader() == SqliteConnections.class.getClassLoader()) out.add(d.getClass().getName());
        }
        return out;
    }

    static ThreadPoolExecutor io() throws Exception {
        return (ThreadPoolExecutor) staticField(PersistenceManager.class, "io");
    }

    static Object staticField(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(null);
    }
}
