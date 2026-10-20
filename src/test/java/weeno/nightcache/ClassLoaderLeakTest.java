package weeno.nightcache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weeno.nightcache.testsupport.FakeServer;
import weeno.nightcache.testsupport.PluginClassLoader;
import weeno.nightcache.testsupport.Threads;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fuites au disable : la lib tourne dans un classloader de plugin simulé (copie de NightCache propre au plugin,
 * comme après relocalisation). Après {@code NightCache.disable()} : aucun thread NightCache, aucune tâche planifiée,
 * registres statiques vides, aucun pilote JDBC du plugin dans {@code DriverManager}, et le classloader du plugin
 * (ainsi que le classloader SQLite isolé) est réellement libéré par le GC.
 */
@Tag("sqlite")
class ClassLoaderLeakTest {

    @TempDir
    Path tmp;

    @BeforeAll
    static void server() {
        FakeServer.install();
        // Comme sur un vrai serveur : DriverManager est initialisé bien avant les plugins (pas pendant leur enable).
        java.sql.DriverManager.getDrivers();
    }

    /** Pilote fourni par le serveur (cas courant : Spigot/Paper embarquent sqlite-jdbc). */
    @Test
    void serverDriver() throws Exception {
        assertNoLeak("LeakServer", new URL[]{classes(), testClasses(), jar(Caffeine.class)}, false);
    }

    /** Pilote shadé dans le plugin, chargé par le classloader du plugin (serveur sans pilote). */
    @Test
    void driverShadedInThePluginClassLoader() throws Exception {
        List<URL> urls = new ArrayList<>(List.of(classes(), testClasses(), jar(Caffeine.class), sqliteJar()));
        URL slf4j = slf4jJar();
        if (slf4j != null) urls.add(slf4j);
        assertNoLeak("LeakShaded", urls.toArray(new URL[0]), true);
    }

    /** Pilote embarqué dans le jar du plugin : chargé dans le classloader SQLite isolé de la lib. */
    @Test
    void driverEmbeddedInThePluginJar() throws Exception {
        // Hors @TempDir : sous Windows, sqlite-jdbc garde le jar ouvert (cache JarURLConnection) jusqu'à la fin de la JVM.
        Path jar = Files.createTempFile(Files.createDirectories(Paths.get(System.getProperty("nightcache.basedir", "."), "target", "leak-jars")), "plugin", ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            Set<String> seen = new HashSet<>();
            addDirectory(out, seen, Paths.get(classes().toURI()));
            addDirectory(out, seen, Paths.get(testClasses().toURI()));
            addJar(out, seen, jar(Caffeine.class));
            addJar(out, seen, sqliteJar());
            URL slf4j = slf4jJar();
            if (slf4j != null) addJar(out, seen, slf4j);
        }
        assertNoLeak("LeakEmbedded", new URL[]{jar.toUri().toURL()}, false); // Bukkit : parent d'abord
    }

    private void assertNoLeak(String name, URL[] urls, boolean ownSqlite) throws Exception {
        PluginClassLoader loader = new PluginClassLoader(urls, ClassLoaderLeakTest.class.getClassLoader(), ownSqlite);
        WeakReference<ClassLoader> pluginRef = new WeakReference<>(loader);
        Map<String, Object> r = run(loader, name);
        loader.close();
        loader = null;

        assertTrue(Threads.awaitNone(5_000).isEmpty(), "threads NightCache vivants : " + Threads.nightCache());
        assertEquals(0, FakeServer.pendingTasks(), "tâches Bukkit encore planifiées");
        for (String phase : new String[]{"afterDisable.", "afterReload."}) {
            for (String k : new String[]{"caches", "pools", "asyncLeaderboards", "stores", "bindings", "hooks",
                    "trackedMaps", "gauges"}) {
                assertEquals(0, r.get(phase + k), phase + k + " non vidé");
            }
            for (String k : new String[]{"plugin", "log", "folder", "isolated"}) {
                assertEquals("null", r.get(phase + k), phase + k + " garde une référence");
            }
        }
        assertEquals(List.of(), r.get("ownDrivers"), "pilote JDBC du plugin encore enregistré");
        assertEquals(List.of(), r.get("ownDriversAfterReload"));
        assertEquals(Boolean.TRUE, r.get("menuPurgedOnQuit"));
        assertEquals(10L, r.get("alice"), "sauvegarde du quit relue après reload");
        assertEquals(20L, r.get("bob"), "sauvegarde finale du disable relue après reload");
        assertEquals("Bob ✓", r.get("bobName"));
        assertEquals(7, r.get("bobKills"));
        assertEquals("Légende", r.get("bobTitle"));

        @SuppressWarnings("unchecked")
        WeakReference<Object> isolatedRef = (WeakReference<Object>) r.get("isolatedLoader");
        r.clear();
        gc(pluginRef, isolatedRef);
        assertNull(pluginRef.get(), "classloader du plugin retenu après disable (fuite)");
        assertNull(isolatedRef.get(), "classloader SQLite isolé retenu après disable (fuite)");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> run(ClassLoader loader, String name) throws Exception {
        Class<?> scenario = Class.forName("weeno.nightcache.LeakScenario", true, loader);
        if (scenario.getClassLoader() != loader) throw new AssertionError("scénario chargé hors du plugin simulé");
        Function<String, Map<String, Object>> f =
                (Function<String, Map<String, Object>>) scenario.getDeclaredConstructor().newInstance();
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader); // comme Bukkit pendant onEnable/onDisable
        try {
            return new java.util.HashMap<>(f.apply(name));
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static void gc(WeakReference<?>... refs) throws InterruptedException {
        for (int i = 0; i < 40; i++) {
            boolean all = true;
            for (WeakReference<?> r : refs) all &= r.get() == null;
            if (all) return;
            System.gc();
            byte[][] pressure = new byte[64][];
            for (int j = 0; j < pressure.length; j++) pressure[j] = new byte[256 * 1024];
            Thread.sleep(50);
        }
    }

    private static URL classes() {
        return jar(NightCache.class);
    }

    private static URL testClasses() {
        return jar(ClassLoaderLeakTest.class);
    }

    /** Pilote shadé par les plugins (Core, Jobs, Casino) : sqlite-jdbc 3.45, copié par Maven. */
    private static URL sqliteJar() throws Exception {
        Path p = Paths.get(System.getProperty("nightcache.basedir", "."), "target", "sqlite-recent", "sqlite-jdbc.jar");
        assertTrue(Files.isRegularFile(p), p.toString());
        return p.toUri().toURL();
    }

    private static URL slf4jJar() {
        try {
            return jar(Class.forName("org.slf4j.LoggerFactory"));
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static URL jar(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }

    private static void addDirectory(JarOutputStream out, Set<String> seen, Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(p)) continue;
                String name = root.relativize(p).toString().replace(File.separatorChar, '/');
                try (InputStream in = Files.newInputStream(p)) {
                    put(out, seen, name, in);
                }
            }
        }
    }

    private static void addJar(JarOutputStream out, Set<String> seen, URL url) throws Exception {
        try (JarFile jar = new JarFile(new File(url.toURI()))) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA")
                        || n.endsWith(".DSA") || n.equals("META-INF/MANIFEST.MF") || n.startsWith("META-INF/versions/"))) {
                    continue;
                }
                if (n.equals("module-info.class")) continue;
                try (InputStream in = jar.getInputStream(e)) {
                    put(out, seen, n, in);
                }
            }
        }
    }

    private static void put(JarOutputStream out, Set<String> seen, String name, InputStream in) throws IOException {
        if (!seen.add(name)) return;
        out.putNextEntry(new JarEntry(name));
        in.transferTo(out);
        out.closeEntry();
    }
}
