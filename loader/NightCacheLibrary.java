package weeno.neptaliacore.core.libs;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Chargement de NightCache depuis {@code plugins/<Plugin>/libs/} au démarrage, avant toute classe de la lib :
 * <ol>
 *   <li>mise à jour depuis la release GitHub {@code latest} si sa version change et garde le même major d'API
 *       (jar vérifié : SHA-256 publié, classe principale, manifeste) ;</li>
 *   <li>sans réseau : copie locale ; sans copie locale valide : jar de secours embarqué dans le plugin ;</li>
 *   <li>relocalisation sous le package du plugin (l'état de NightCache est statique : une copie par plugin, et
 *       le code du plugin est déjà relocalisé ainsi par le shadowJar), mise en cache par empreinte ;</li>
 *   <li>injection dans le classloader du plugin.</li>
 * </ol>
 * N'utilise aucune classe de NightCache. main-thread-only (onLoad)
 */
public final class NightCacheLibrary {

    /** Package relocalisé (format interne JVM), identique à la relocation du shadowJar. */
    private static final String RELOCATED = "weeno/neptaliacore/libs/nightcache/";
    /**
     * Mêmes relocations que le shadowJar pour les classes que NightCache partage avec le plugin : Gson est celui
     * embarqué par le plugin (2.11, records pris en charge), pas celui du serveur (2.2.4 en 1.8). Les préfixes
     * d'origine sont assemblés à l'exécution, le shadowJar réécrivant les chaînes littérales.
     */
    static final List<JarRelocator.Rule> RULES = List.of(
            new JarRelocator.Rule(LibraryJar.ORIGINAL, RELOCATED),
            new JarRelocator.Rule(String.join("/", "com", "google", "gson", ""), "weeno/neptaliacore/shaded/gson/"));
    /** Pas d'extension .jar : le shadowJar dépaquetterait le fichier dans le plugin. */
    private static final String EMBEDDED = "nightcache/NightCache.lib";
    private static final String JAR = "NightCache.jar";
    private static final String VERSION = "NightCache.version";
    private static final String CACHE_DIR = ".relocated";
    private static final String DEFAULT_RELEASES = "https://github.com/NightProjectS/NightCache/releases";

    private NightCacheLibrary() {
    }

    /**
     * main-thread-only; charge NightCache dans le classloader de {@code plugin}.
     *
     * @return la version chargée
     * @throws IllegalStateException si aucune copie utilisable n'a pu être chargée (le plugin doit se désactiver)
     */
    public static @NotNull String load(@NotNull JavaPlugin plugin) {
        Logger log = plugin.getLogger();
        Path libs = plugin.getDataFolder().toPath().resolve("libs");
        Path jar = libs.resolve(JAR);
        Path versionFile = libs.resolve(VERSION);
        try {
            Files.createDirectories(libs);
            LibraryJar embedded = embedded(plugin, libs);
            ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("libs.nightcache");
            boolean autoUpdate = cfg == null || cfg.getBoolean("auto-update", true);
            String releases = cfg == null ? DEFAULT_RELEASES : cfg.getString("releases", DEFAULT_RELEASES);

            if (autoUpdate) update(log, releases, jar, versionFile, embedded.api());
            LibraryJar local = LibraryJar.inspect(jar);
            if (local == null || local.api() != embedded.api()) {
                if (local != null) log.warning("[NightCache] libs/" + JAR + " (API " + local.api() + ") incompatible, copie embarquée utilisée.");
                Files.copy(embedded.path(), jar, StandardCopyOption.REPLACE_EXISTING);
                Files.writeString(versionFile, embedded.version(), StandardCharsets.UTF_8);
                local = LibraryJar.inspect(jar);
                if (local == null) throw new IllegalStateException("copie embarquée illisible");
            }

            Path relocated = relocated(libs, local);
            ClassPathInjector.add(plugin.getClass().getClassLoader(), relocated.toUri().toURL());
            log.info("[NightCache] " + local.version() + " chargé depuis libs/" + JAR);
            return local.version();
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("NightCache n'a pas pu être chargé depuis " + libs, e);
        } finally {
            deleteQuietly(libs.resolve(JAR + ".part"), log);
        }
    }

    /** Jar de secours livré dans le plugin, extrait à côté (le lire en place est impossible depuis le jar). */
    private static @NotNull LibraryJar embedded(@NotNull JavaPlugin plugin, @NotNull Path libs) throws IOException {
        Path out = libs.resolve(CACHE_DIR).resolve("embedded.jar");
        Files.createDirectories(out.getParent());
        try (InputStream in = plugin.getResource(EMBEDDED)) {
            if (in == null) throw new IOException("ressource " + EMBEDDED + " absente du plugin");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        LibraryJar jar = LibraryJar.inspect(out);
        if (jar == null) throw new IOException("ressource " + EMBEDDED + " invalide");
        return jar;
    }

    private static void update(@NotNull Logger log, @NotNull String releases, @NotNull Path jar, @NotNull Path versionFile, int api) {
        try {
            String remote = ReleaseClient.latestVersion(releases);
            String current = Files.isRegularFile(versionFile) ? Files.readString(versionFile, StandardCharsets.UTF_8).trim() : "";
            if (remote.equals(current) && LibraryJar.inspect(jar) != null) return;
            int remoteApi = LibraryJar.major(remote);
            if (remoteApi != api) {
                log.warning("[NightCache] release " + remote + " ignorée : API " + remoteApi + ", ce plugin attend l'API " + api + '.');
                return;
            }
            Path part = jar.resolveSibling(JAR + ".part");
            ReleaseClient.download(releases, remote, part);
            LibraryJar downloaded = LibraryJar.inspect(part);
            if (downloaded == null || downloaded.api() != api || !downloaded.version().equals(remote)) {
                log.warning("[NightCache] release " + remote + " rejetée : jar invalide ou manifeste incohérent.");
                return;
            }
            Files.move(part, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Files.writeString(versionFile, remote, StandardCharsets.UTF_8);
            log.info("[NightCache] mis à jour : " + (current.isEmpty() ? "?" : current) + " -> " + remote);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "[NightCache] mise à jour impossible (" + e.getMessage() + "), copie locale conservée.");
        }
    }

    /**
     * Jar relocalisé en cache, nommé par l'empreinte du jar source et des règles (un plugin mis à jour avec d'autres
     * relocations ne réutilise pas un ancien cache) ; les anciennes versions sont supprimées.
     */
    private static @NotNull Path relocated(@NotNull Path libs, @NotNull LibraryJar source) throws IOException {
        Path dir = libs.resolve(CACHE_DIR);
        Files.createDirectories(dir);
        String name = "NightCache-" + source.version() + '-' + source.sha256().substring(0, 12) + '-'
                + Integer.toHexString(RULES.hashCode()) + ".jar";
        Path target = dir.resolve(name);
        if (!Files.isRegularFile(target) || LibraryJar.inspectRelocated(target, RELOCATED) == null) {
            Path tmp = dir.resolve(name + ".part");
            JarRelocator.relocate(source.path(), tmp, RULES);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "NightCache-*.jar")) {
            for (Path p : old) if (!p.equals(target)) deleteQuietly(p, null);
        }
        return target;
    }

    private static void deleteQuietly(@NotNull Path p, @Nullable Logger log) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            // Fichier verrouillé (Windows) : sans conséquence, il sera remplacé au prochain démarrage.
            if (log != null) log.log(Level.FINE, "[NightCache] suppression de " + p, e);
        }
    }
}
