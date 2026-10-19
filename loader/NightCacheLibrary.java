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
import java.util.jar.JarFile;
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

            if (autoUpdate) update(log, releases, jar, versionFile, embedded);
            LibraryJar local = LibraryJar.inspect(jar);
            if (!usable(local, embedded)) {
                if (local != null) {
                    log.warning("[NightCache] libs/" + JAR + " " + local.version() + " (API " + local.api() + ") plus ancien que la copie "
                            + "embarquée " + embedded.version() + " ou incompatible : copie embarquée utilisée.");
                }
                install(embedded.path(), jar);
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
        }
    }

    /**
     * Copie utilisable : même major d'API que la copie embarquée et jamais plus ancienne qu'elle (le plugin est
     * compilé contre l'embarquée : une version antérieure pourrait manquer de méthodes, d'où NoSuchMethodError).
     */
    static boolean usable(@Nullable LibraryJar local, @NotNull LibraryJar embedded) {
        return local != null && local.api() == embedded.api() && LibraryJar.compareVersions(local.version(), embedded.version()) >= 0;
    }

    /** Jar de secours livré dans le plugin, extrait à côté (le lire en place est impossible depuis le jar). */
    private static @NotNull LibraryJar embedded(@NotNull JavaPlugin plugin, @NotNull Path libs) throws IOException {
        Path out = libs.resolve(CACHE_DIR).resolve("embedded.jar");
        Files.createDirectories(out.getParent());
        Path tmp = Files.createTempFile(out.getParent(), "embedded", ".part");
        try {
            try (InputStream in = plugin.getResource(EMBEDDED)) {
                if (in == null) throw new IOException("ressource " + EMBEDDED + " absente du plugin");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            install(tmp, out);
        } finally {
            deleteQuietly(tmp, null);
        }
        LibraryJar jar = LibraryJar.inspect(out);
        if (jar == null) throw new IOException("ressource " + EMBEDDED + " invalide");
        return jar;
    }

    private static void update(@NotNull Logger log, @NotNull String releases, @NotNull Path jar, @NotNull Path versionFile,
                               @NotNull LibraryJar embedded) {
        Path part = null;
        try {
            String remote = ReleaseClient.latestVersion(releases);
            String current = Files.isRegularFile(versionFile) ? Files.readString(versionFile, StandardCharsets.UTF_8).trim() : "";
            if (remote.equals(current) && usable(LibraryJar.inspect(jar), embedded)) return;
            int remoteApi = LibraryJar.major(remote);
            if (remoteApi != embedded.api()) {
                log.warning("[NightCache] release " + remote + " ignorée : API " + remoteApi + ", ce plugin attend l'API " + embedded.api() + '.');
                return;
            }
            if (LibraryJar.compareVersions(remote, embedded.version()) < 0) {
                log.warning("[NightCache] release " + remote + " ignorée : plus ancienne que la copie embarquée " + embedded.version() + '.');
                return;
            }
            part = Files.createTempFile(jar.getParent(), "NightCache", ".part"); // nom unique : dossier partagé possible
            ReleaseClient.download(releases, remote, part);
            LibraryJar downloaded = LibraryJar.inspect(part);
            if (downloaded == null || downloaded.api() != embedded.api() || !downloaded.version().equals(remote)) {
                log.warning("[NightCache] release " + remote + " rejetée : jar invalide ou manifeste incohérent.");
                return;
            }
            install(part, jar);
            Files.writeString(versionFile, remote, StandardCharsets.UTF_8);
            log.info("[NightCache] mis à jour : " + (current.isEmpty() ? "?" : current) + " -> " + remote);
        } catch (IOException | RuntimeException e) {
            log.log(Level.WARNING, "[NightCache] mise à jour impossible (" + e.getMessage() + "), copie locale conservée.");
        } finally {
            if (part != null) deleteQuietly(part, log);
        }
    }

    /**
     * Remplace {@code target} par {@code source} de façon atomique. Si un autre serveur partageant le dossier l'a
     * remplacé au même instant et que le résultat est un jar valide, ce n'est pas une erreur.
     */
    private static void install(@NotNull Path source, @NotNull Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            if (Files.isRegularFile(target) && isJar(target)) return;
            throw e;
        }
    }

    private static boolean isJar(@NotNull Path p) {
        try (JarFile ignored = new JarFile(p.toFile())) {
            return true;
        } catch (IOException e) {
            return false;
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
            Path tmp = Files.createTempFile(dir, "relocating", ".part"); // nom unique : dossier partagé possible
            try {
                JarRelocator.relocate(source.path(), tmp, RULES);
                install(tmp, target);
            } finally {
                deleteQuietly(tmp, null);
            }
            if (LibraryJar.inspectRelocated(target, RELOCATED) == null) throw new IOException("jar relocalisé invalide : " + target);
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
