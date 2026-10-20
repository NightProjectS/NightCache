package weeno.nightcache.save;

import org.junit.jupiter.api.Test;
import weeno.nightcache.caches.CaffeineCache;
import weeno.nightcache.interfaces.ManagedCache;
import weeno.nightcache.interfaces.PersistentStore;
import weeno.nightcache.objects.CacheSpec;
import weeno.nightcache.testsupport.Alloc;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cycle de vie joueur de {@link PlayerBinding} sans serveur : store en mémoire (pannes injectables), writer simulé
 * (file de lots exécutée à la demande) et horloge manuelle.
 */
class PlayerBindingTest {

    private static final long RETRY_NS = 5_000_000_000L;

    /** Store en mémoire ; un lot contenant une valeur « BAD… » échoue en entier, comme une transaction SQLite. */
    static final class MemoryStore implements PersistentStore<UUID, String> {
        final Map<UUID, String> rows = new ConcurrentHashMap<>();
        volatile boolean down;
        int batches;

        @Override public String name() { return "mem"; }

        @Override public String load(UUID key) {
            if (down) throw new IllegalStateException("disque indisponible");
            return rows.get(key);
        }

        @Override public Map<UUID, String> loadAll() { return Map.copyOf(rows); }

        @Override public void save(UUID key, String value) {
            if (down || value.startsWith("BAD")) throw new IllegalStateException("écriture refusée : " + value);
            rows.put(key, value);
        }

        @Override public void saveAll(Map<UUID, String> entries) {
            if (entries.isEmpty()) return;
            batches++;
            if (down) throw new IllegalStateException("disque indisponible");
            for (String v : entries.values()) if (v.startsWith("BAD")) throw new IllegalStateException("lot refusé");
            rows.putAll(entries);
        }

        @Override public void delete(UUID key) { rows.remove(key); }
        @Override public long count() { return rows.size(); }
        @Override public void close() {}
    }

    /** Writer simulé : les lots s'accumulent et ne s'exécutent qu'à {@link #runAll()}. */
    static final class Writer implements Consumer<Runnable> {
        final ArrayDeque<Runnable> jobs = new ArrayDeque<>();
        boolean reject;

        @Override public void accept(Runnable r) {
            if (reject) throw new java.util.concurrent.RejectedExecutionException("file pleine");
            jobs.add(r);
        }

        void runAll() { for (Runnable r; (r = jobs.poll()) != null; ) r.run(); }
    }

    final MemoryStore store = new MemoryStore();
    final ManagedCache<UUID, String> cache = new CaffeineCache<>(CacheSpec.persistent("profils"));
    final Writer writer = new Writer();
    final long[] now = {0L};

    PlayerBinding<String> binding() {
        return binding(UnaryOperator.identity());
    }

    PlayerBinding<String> binding(UnaryOperator<String> snapshot) {
        return new PlayerBinding<>(cache, store, id -> "neuf", snapshot, Objects::equals, () -> now[0]);
    }

    /** Un tick serveur suivi de l'exécution du lot éventuel, puis de l'application de son résultat. */
    void cycle(PlayerBinding<String> b) {
        b.tick(writer);
        writer.runAll();
        b.tick(writer);
    }

    void settle(PlayerBinding<String> b) {
        for (int i = 0; i < 200 && (b.pending() > 0 || !writer.jobs.isEmpty() || inFlight(b)); i++) {
            cycle(b);
            now[0] += RETRY_NS;
        }
        cycle(b);
    }

    // ─── cycle nominal ─────────────────────────────────────────────────────

    @Test
    void joinLoadsDefaultsThenQuitSavesAndEvicts() throws Exception {
        PlayerBinding<String> b = binding();
        UUID id = UUID.randomUUID();
        assertTrue(b.load(id));
        cycle(b);
        assertEquals("neuf", b.get(id));
        cache.put(id, "niveau 2");
        b.saveAndEvict(id);
        settle(b);
        assertEquals("niveau 2", store.rows.get(id));
        assertNull(cache.getIfPresent(id), "profil évincé après une sauvegarde réussie");
        assertEquals(0, admitted(b).size());
    }

    @Test
    void rejoinDuringSaveKeepsTheProfile() {
        PlayerBinding<String> b = binding();
        UUID id = UUID.randomUUID();
        b.load(id);
        cycle(b);
        cache.put(id, "v1");
        b.saveAndEvict(id);
        b.tick(writer);       // lot de sauvegarde parti
        b.load(id);           // revient avant la fin de l'écriture
        writer.runAll();
        b.tick(writer);
        assertEquals("v1", cache.getIfPresent(id), "un joueur revenu n'est jamais évincé");
        assertEquals("v1", store.rows.get(id));
    }

    @Test
    void modificationDuringSaveIsSavedAgainBeforeEviction() {
        PlayerBinding<String> b = binding();
        UUID id = UUID.randomUUID();
        b.load(id);
        cycle(b);
        cache.put(id, "v1");
        b.saveAndEvict(id);
        b.tick(writer);
        cache.put(id, "v2"); // une partie en cours modifie encore le profil
        writer.runAll();
        settle(b);
        assertEquals("v2", store.rows.get(id), "dernier état écrit");
        assertNull(cache.getIfPresent(id));
    }

    @Test
    void retainedProfileOutlivesLogoutUntilReleased() {
        PlayerBinding<String> b = binding();
        UUID id = UUID.randomUUID();
        b.load(id);
        cycle(b);
        assertTrue(b.retain(id));
        cache.put(id, "en jeu");
        b.saveAndEvict(id);
        settle(b);
        assertEquals("en jeu", cache.getIfPresent(id), "retenu par une partie en cours");
        b.release(id);
        settle(b);
        assertNull(cache.getIfPresent(id));
        assertEquals("en jeu", store.rows.get(id));
        assertFalse(b.retain(UUID.randomUUID()), "rien à retenir pour un profil absent");
    }

    // ─── pannes ────────────────────────────────────────────────────────────

    @Test
    void oneUnsavableProfileDoesNotBlockTheOthers() {
        // Un seul profil impossible à écrire faisait échouer tout le lot (128 joueurs) à chaque nouvel essai :
        // aucun des autres n'était jamais sauvegardé ni évincé.
        PlayerBinding<String> b = binding();
        List<UUID> ids = join(b, 10);
        for (int i = 0; i < ids.size(); i++) cache.put(ids.get(i), i == 3 ? "BAD" : "ok" + i);
        for (UUID id : ids) b.saveAndEvict(id);
        settle(b);
        for (int i = 0; i < ids.size(); i++) {
            if (i == 3) {
                assertEquals("BAD", cache.getIfPresent(ids.get(i)), "profil non écrit : gardé en RAM pour réessayer");
                assertFalse(store.rows.containsKey(ids.get(i)));
            } else {
                assertEquals("ok" + i, store.rows.get(ids.get(i)), "profil " + i + " jamais écrit");
                assertNull(cache.getIfPresent(ids.get(i)));
            }
        }
        cache.put(ids.get(3), "réparé");
        settle(b);
        assertEquals("réparé", store.rows.get(ids.get(3)));
        assertNull(cache.getIfPresent(ids.get(3)));
    }

    @Test
    void failingSnapshotDoesNotLoseTheRestOfTheBatch() {
        // Une exception de snapshot sortait de tick() après avoir retiré les UUID de la file : les autres
        // profils du lot n'étaient plus jamais sauvegardés ni évincés.
        PlayerBinding<String> b = binding(v -> {
            if (v.equals("SNAP")) throw new IllegalStateException("copie impossible");
            return v;
        });
        List<UUID> ids = join(b, 5);
        for (int i = 0; i < ids.size(); i++) cache.put(ids.get(i), i == 0 ? "SNAP" : "ok" + i);
        for (UUID id : ids) b.saveAndEvict(id);
        settle(b);
        for (int i = 1; i < ids.size(); i++) assertEquals("ok" + i, store.rows.get(ids.get(i)));
        assertEquals("SNAP", cache.getIfPresent(ids.get(0)), "jamais évincé sans écriture");
        cache.put(ids.get(0), "ok0");
        settle(b);
        assertEquals("ok0", store.rows.get(ids.get(0)), "réessayé ensuite");
    }

    @Test
    void storeOutageRetriesWithBackoffAndLosesNothing() {
        PlayerBinding<String> b = binding();
        List<UUID> ids = join(b, 3);
        for (UUID id : ids) cache.put(id, "v");
        store.down = true;
        for (UUID id : ids) b.saveAndEvict(id);
        cycle(b);
        int attempts = store.batches;
        for (int i = 0; i < 20; i++) cycle(b); // même instant : pas de nouvel essai avant 5 s
        assertTrue(store.batches <= attempts + 1, "pas de rafale d'essais pendant la panne");
        for (UUID id : ids) assertEquals("v", cache.getIfPresent(id), "rien n'est évincé pendant la panne");
        store.down = false;
        now[0] += RETRY_NS;
        settle(b);
        for (UUID id : ids) {
            assertEquals("v", store.rows.get(id));
            assertNull(cache.getIfPresent(id));
        }
    }

    @Test
    void rejectedBatchIsRequeued() {
        PlayerBinding<String> b = binding();
        UUID id = UUID.randomUUID();
        writer.reject = true;
        b.load(id);
        b.tick(writer);
        assertNull(b.get(id));
        writer.reject = false;
        now[0] += RETRY_NS;
        settle(b);
        assertEquals("neuf", b.get(id), "chargement réessayé après un refus du writer");
    }

    @Test
    void failedLoadIsRetriedAndNeverOverwritesDisk() {
        PlayerBinding<String> b = binding();
        UUID id = UUID.randomUUID();
        store.rows.put(id, "existant");
        store.down = true;
        b.load(id);
        cycle(b);
        assertNull(b.get(id), "pas de profil par défaut si la lecture a échoué");
        store.down = false;
        now[0] += RETRY_NS;
        settle(b);
        assertEquals("existant", b.get(id));
    }

    @Test
    void profilesDroppedFromTheCacheNeverFillTheAdmissionTable() throws Exception {
        // Un profil sorti du cache (TTL, invalidate) avant sa sauvegarde de quit restait « admis » pour toujours :
        // au bout de 20 000 joueurs, plus aucun chargement n'était accepté (profils vides pour tout le monde).
        PlayerBinding<String> b = binding();
        for (int i = 0; i < 20_500; i++) {
            UUID id = new UUID(7L, i);
            assertTrue(b.load(id), "chargement refusé au joueur n°" + i);
            cycle(b);
            b.saveAndEvict(id);
            cache.invalidate(id); // TTL / invalidate entre le quit et l'écriture
            cycle(b);
        }
        assertTrue(admitted(b).size() < 1_000, "table d'admission : " + admitted(b).size());
    }

    // ─── arrêt ─────────────────────────────────────────────────────────────

    @Test
    void finalSaveWritesEveryProfileDespiteABadOne() {
        PlayerBinding<String> b = binding(v -> {
            if (v.equals("SNAP")) throw new IllegalStateException("copie impossible");
            return v;
        });
        List<UUID> ids = join(b, 300);
        for (int i = 0; i < ids.size(); i++) cache.put(ids.get(i), i == 10 ? "SNAP" : i == 200 ? "BAD" : "v" + i);
        b.finalSave().run();
        for (int i = 0; i < ids.size(); i++) {
            if (i == 10 || i == 200) continue;
            assertEquals("v" + i, store.rows.get(ids.get(i)), "profil " + i + " perdu à l'arrêt");
        }
        assertEquals(298, store.rows.size());
    }

    @Test
    void finalSaveIsDetachedFromLaterMutations() {
        PlayerBinding<String> b = binding();
        UUID id = join(b, 1).get(0);
        cache.put(id, "à l'arrêt");
        Runnable job = b.finalSave();
        cache.put(id, "après");
        job.run();
        assertEquals("à l'arrêt", store.rows.get(id));
    }

    // ─── modèle aléatoire ──────────────────────────────────────────────────

    @Test
    void randomSessionsNeverLoseTheLastValue() throws Exception {
        for (long seed = 1; seed <= 5; seed++) {
            MemoryStore s = new MemoryStore();
            ManagedCache<UUID, String> c = new CaffeineCache<>(CacheSpec.persistent("p" + seed));
            Writer w = new Writer();
            long[] clock = {0};
            PlayerBinding<String> b = new PlayerBinding<>(c, s, id -> "neuf", UnaryOperator.identity(), Objects::equals,
                    () -> clock[0]);
            Random r = new Random(seed);
            UUID[] players = new UUID[40];
            for (int i = 0; i < players.length; i++) players[i] = new UUID(seed, i);
            Set<UUID> online = new HashSet<>();
            Map<UUID, Integer> pins = new HashMap<>();
            Map<UUID, String> expected = new HashMap<>();
            for (int op = 0; op < 5_000; op++) {
                UUID id = players[r.nextInt(players.length)];
                switch (r.nextInt(8)) {
                    case 0 -> { b.load(id); online.add(id); }
                    case 1 -> { b.saveAndEvict(id); online.remove(id); }
                    case 2, 3 -> {
                        if (c.getIfPresent(id) != null && (online.contains(id) || pins.containsKey(id))) {
                            String v = "v" + op;
                            c.put(id, v);
                            expected.put(id, v);
                            b.save(id);
                        }
                    }
                    case 4 -> { if (b.retain(id)) pins.merge(id, 1, Integer::sum); }
                    case 5 -> {
                        if (pins.containsKey(id)) {
                            pins.computeIfPresent(id, (k, n) -> n == 1 ? null : n - 1);
                            b.release(id);
                        }
                    }
                    case 6 -> { if (r.nextInt(10) == 0) b.flushAll(); }
                    default -> {
                        b.tick(w);
                        if (r.nextBoolean()) w.runAll();
                        clock[0] += r.nextInt(3) * RETRY_NS;
                    }
                }
            }
            for (UUID id : players) {
                while (pins.containsKey(id)) {
                    pins.computeIfPresent(id, (k, n) -> n == 1 ? null : n - 1);
                    b.release(id);
                }
                b.saveAndEvict(id);
            }
            for (int i = 0; i < 100; i++) {
                b.tick(w);
                w.runAll();
                clock[0] += RETRY_NS;
            }
            for (Map.Entry<UUID, String> e : expected.entrySet()) {
                assertEquals(e.getValue(), s.rows.get(e.getKey()), "graine " + seed + " : dernière valeur perdue");
            }
            assertEquals(0, c.asMap().size(), "graine " + seed + " : profils hors ligne encore en RAM");
            assertEquals(0, b.pending(), "graine " + seed);
            assertEquals(0, admitted(b).size(), "graine " + seed);
        }
    }

    // ─── allocation ────────────────────────────────────────────────────────

    @Test
    void idleTickAllocatesNothing() {
        PlayerBinding<String> b = binding();
        List<UUID> ids = join(b, 50);
        assertEquals(50, cache.asMap().size());
        long bytes = Alloc.bytes(50_000, 100_000, () -> b.tick(writer));
        assertTrue(bytes <= 512, "tick sans travail : " + bytes + " octets");
        assertTrue(writer.jobs.isEmpty());
        assertEquals(50, ids.size());
    }

    // ─── utilitaires ───────────────────────────────────────────────────────

    List<UUID> join(PlayerBinding<String> b, int n) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = UUID.randomUUID();
            b.load(id);
            ids.add(id);
        }
        settle(b);
        for (UUID id : ids) assertEquals("neuf", b.get(id));
        return ids;
    }

    @SuppressWarnings("unchecked")
    static Collection<UUID> admitted(PlayerBinding<?> b) throws Exception {
        Field f = PlayerBinding.class.getDeclaredField("admitted");
        f.setAccessible(true);
        return (Collection<UUID>) f.get(b);
    }

    static boolean inFlight(PlayerBinding<?> b) {
        try {
            Field f = PlayerBinding.class.getDeclaredField("inFlight");
            f.setAccessible(true);
            return f.getBoolean(b);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
