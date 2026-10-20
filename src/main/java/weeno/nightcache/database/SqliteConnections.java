package weeno.nightcache.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.utils.Log;

import java.io.IOException;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Properties;

/**
 * Ouverture des connexions SQLite, compatible de Spigot 1.8 à Paper récent :
 * <ol>
 *   <li>si le jar <b>du plugin</b> embarque {@code org.sqlite.JDBC} (sqlite-jdbc shadé, <b>non relocalisé</b>), il est
 *       chargé dans un unique classloader isolé partagé par tous les stores du plugin (le pilote du serveur 1.8.8 date
 *       de 2010). Le jar est cherché dans les seules URL du classloader du plugin, sans passer par le parent : NightCache
 *       est lui-même chargé depuis {@code plugins/<Plugin>/libs/.relocated/}, un jar distinct de celui du plugin ;</li>
 *   <li>sinon (ou si ce pilote est inutilisable) le pilote fourni par le serveur est utilisé.</li>
 * </ol>
 * Les connexions sont ouvertes directement sur l'instance du pilote (jamais via {@code DriverManager}) ; au
 * {@link #release()}, tout pilote chargé par le classloader du plugin ou le classloader isolé est retiré de
 * {@code DriverManager}, qui sinon retiendrait le plugin entier après le disable.
 * Thread: confiné au writer de {@code PersistenceManager}.
 */
public final class SqliteConnections {

    private static final String DRIVER = "org.sqlite.JDBC";
    private static final String DRIVER_CLASS_FILE = "org/sqlite/JDBC.class";

    private static @Nullable URLClassLoader isolated;
    private static boolean isolatedBroken;
    private static boolean announced;

    private SqliteConnections() {}

    static @NotNull Connection open(@NotNull File file) throws SQLException {
        String url = "jdbc:sqlite:" + file.getAbsolutePath();
        if (!isolatedBroken) {
            URL jar = embeddedDriverJar();
            if (jar != null) {
                try {
                    if (isolated == null) {
                        isolated = new URLClassLoader(isolatedPath(jar), ClassLoader.getPlatformClassLoader());
                    }
                    Class<?> bridge = isolated.loadClass(SqliteDriver.class.getName());
                    Connection c = (Connection) bridge.getMethod("open", String.class).invoke(null, url);
                    announce("pilote SQLite embarqué par le plugin (classloader isolé)");
                    return c;
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    if (!(cause instanceof LinkageError)) {
                        throw new SQLException("ouverture SQLite isolée : " + file.getName(), cause);
                    }
                    abandonIsolated(cause);
                } catch (ReflectiveOperationException | LinkageError e) {
                    abandonIsolated(e);
                }
            }
        }
        Driver driver;
        try {
            // Pilote du serveur, visible car le classloader du plugin délègue au serveur. Instance directe : ne dépend
            // pas de son enregistrement dans DriverManager (retiré au disable s'il venait du plugin).
            driver = (Driver) Class.forName(DRIVER, true, SqliteConnections.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException e) {
            throw new SQLException("Aucun pilote SQLite : le serveur n'en fournit pas, "
                    + "shader org.xerial:sqlite-jdbc (sans relocation) dans le plugin.", e);
        }
        Connection c = driver.connect(url, new Properties());
        if (c == null) throw new SQLException("URL SQLite non reconnue : " + url);
        announce("pilote SQLite du serveur");
        return c;
    }

    /** Pilote embarqué inutilisable (dépendance manquante…) : repli définitif sur celui du serveur. */
    private static void abandonIsolated(@NotNull Throwable e) {
        isolatedBroken = true;
        URLClassLoader l = isolated;
        isolated = null;
        closeQuietly(l);
        Log.severe("pilote SQLite embarqué inutilisable, repli sur le pilote du serveur", e);
    }

    /** Classpath isolé : le jar de NightCache (pour {@link SqliteDriver}, nom relocalisé) puis celui du pilote. */
    private static @NotNull URL[] isolatedPath(@NotNull URL driverJar) {
        CodeSource src = SqliteConnections.class.getProtectionDomain().getCodeSource();
        URL own = src == null ? null : src.getLocation();
        if (own == null || own.equals(driverJar)) return new URL[]{driverJar};
        return new URL[]{own, driverJar};
    }

    /**
     * @return le jar du plugin qui contient sqlite-jdbc, cherché dans les seules URL du classloader du plugin (jamais
     * le serveur), ou {@code null} : classloader non {@link URLClassLoader}, pilote absent ou pas dans un jar.
     */
    static @Nullable URL embeddedDriverJar() {
        ClassLoader loader = SqliteConnections.class.getClassLoader();
        if (!(loader instanceof URLClassLoader ucl)) return null;
        try {
            URL entry = ucl.findResource(DRIVER_CLASS_FILE);
            if (entry == null || !"jar".equals(entry.getProtocol())) return null;
            return ((JarURLConnection) entry.openConnection()).getJarFileURL();
        } catch (IOException | ClassCastException | SecurityException e) {
            return null;
        }
    }

    private static void announce(@NotNull String what) {
        if (announced) return;
        announced = true;
        Log.info("SQLite : " + what);
    }

    /**
     * Libère le classloader isolé et retire de {@code DriverManager} tout pilote chargé par le classloader du plugin
     * ou le classloader isolé, au shutdown. Thread: writer ou après son arrêt.
     */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void release() {
        URLClassLoader l = isolated;
        isolated = null;
        announced = false;
        isolatedBroken = false;
        if (l != null) {
            try {
                l.loadClass(SqliteDriver.class.getName()).getMethod("deregister").invoke(null);
            } catch (ReflectiveOperationException | LinkageError e) {
                Log.severe("désenregistrement du pilote SQLite isolé", e);
            }
        }
        deregisterDrivers(SqliteConnections.class.getClassLoader(), l);
        closeQuietly(l);
    }

    private static void deregisterDrivers(@Nullable ClassLoader own, @Nullable ClassLoader iso) {
        List<Driver> ours = new ArrayList<>();
        try {
            Enumeration<Driver> all = DriverManager.getDrivers();
            while (all.hasMoreElements()) {
                Driver d = all.nextElement();
                ClassLoader dl = d.getClass().getClassLoader();
                if (dl != null && (dl == own || dl == iso)) ours.add(d);
            }
            for (Driver d : ours) DriverManager.deregisterDriver(d);
        } catch (SQLException | SecurityException e) {
            Log.severe("désenregistrement du pilote SQLite", e);
        }
    }

    private static void closeQuietly(@Nullable URLClassLoader l) {
        if (l == null) return;
        try {
            l.close();
        } catch (IOException e) {
            Log.severe("fermeture du classloader SQLite isolé", e);
        }
    }
}
