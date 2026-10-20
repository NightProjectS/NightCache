package weeno.nightcache;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import weeno.nightcache.testsupport.PluginClassLoader;
import weeno.nightcache.testsupport.Threads;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Choix du pilote SQLite depuis le chargement externe de NightCache : la lib vit dans
 * {@code plugins/<P>/libs/.relocated/NightCache-….jar}, un jar DISTINCT du jar du plugin. Quand le plugin embarque
 * sqlite-jdbc 3.45 (Core, Jobs, Casino), c'est ce pilote qui doit servir, pas le 3.7.2 du serveur 1.8 ; s'il est
 * inutilisable, repli propre sur le pilote du serveur.
 */
@Tag("sqlite")
class PluginDriverSelectionTest {

    private static final Path RECENT = Paths.get(System.getProperty("nightcache.basedir", "."),
            "target", "sqlite-recent", "sqlite-jdbc.jar");

    @TempDir
    Path tmp;

    @Test
    void driverShadedInThePluginJarWinsOverTheServerOne() throws Exception {
        Path plugin = pluginJar(true);
        String used = run(plugin);
        assertTrue(used.startsWith("3.45"), "pilote retenu : " + used + " (attendu : celui du plugin, 3.45)");
        assertTrue(used.endsWith("|embarqué"), used);
    }

    @Test
    void unusableEmbeddedDriverFallsBackToTheServerOne() throws Exception {
        // sqlite-jdbc 3.45 sans slf4j (dépendance non shadée) : le pilote isolé ne peut pas s'initialiser.
        Path plugin = pluginJar(false);
        String used = run(plugin);
        String expected = System.getProperty("nightcache.sqlite", "");
        assertTrue(used.endsWith("|serveur"), "repli sur le pilote du serveur (" + expected + ") : " + used);
    }

    private String run(Path pluginJar) throws Exception {
        assertTrue(Files.isRegularFile(RECENT), "pilote récent copié par Maven : " + RECENT);
        URL[] urls = {pluginJar.toUri().toURL(), jar(NightCache.class), jar(PluginDriverSelectionTest.class),
                jar(Caffeine.class)};
        // Bukkit : délégation au serveur d'abord (le 3.7.2 du classpath de test joue le serveur 1.8).
        try (PluginClassLoader loader = new PluginClassLoader(urls, getClass().getClassLoader(), false)) {
            @SuppressWarnings("unchecked")
            Function<String, String> f = (Function<String, String>) Class.forName("weeno.nightcache.DriverScenario", true, loader)
                    .getDeclaredConstructor().newInstance();
            return f.apply(tmp.resolve("Data").resolve("P").toString());
        } finally {
            assertTrue(Threads.awaitNone(5_000).isEmpty(), "threads : " + Threads.nightCache());
        }
    }

    private Path pluginJar(boolean withSlf4j) throws Exception {
        Path jar = tmp.resolve(withSlf4j ? "plugin.jar" : "plugin-sans-slf4j.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            Set<String> seen = new HashSet<>();
            out.putNextEntry(new JarEntry("plugin.yml"));
            out.write("name: P\nmain: p.Main\nversion: 1\n".getBytes());
            out.closeEntry();
            copy(out, seen, RECENT.toFile());
            if (withSlf4j) copy(out, seen, new File(jar(Class.forName("org.slf4j.LoggerFactory")).toURI()));
        }
        return jar;
    }

    private static void copy(JarOutputStream out, Set<String> seen, File source) throws Exception {
        try (JarFile in = new JarFile(source)) {
            Enumeration<JarEntry> entries = in.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String n = e.getName();
                if (e.isDirectory() || n.startsWith("META-INF/") && !n.startsWith("META-INF/maven/") || n.equals("module-info.class") || !seen.add(n)) continue;
                out.putNextEntry(new JarEntry(n));
                try (InputStream is = in.getInputStream(e)) {
                    is.transferTo(out);
                }
                out.closeEntry();
            }
        }
    }

    private static URL jar(Class<?> type) {
        return type.getProtectionDomain().getCodeSource().getLocation();
    }
}
