package weeno.nightcache.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import weeno.nightcache.utils.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Ouverture des connexions SQLite, compatible de Spigot 1.8 à Paper récent :
 * <ol>
 *   <li>si le jar du plugin embarque {@code org.sqlite.JDBC} (sqlite-jdbc shadé, <b>non relocalisé</b>),
 *       il est chargé dans un unique classloader isolé partagé par tous les stores du plugin
 *       (le pilote du serveur 1.8.8 date de 2010) ;</li>
 *   <li>sinon le pilote fourni par le serveur ({@code DriverManager}) est utilisé : zéro octet
 *       ajouté au jar, suffisant (WAL supporté depuis SQLite 3.7.0).</li>
 * </ol>
 * Thread: confiné au writer de {@code PersistenceManager}.
 */
public final class SqliteConnections {

    private static @Nullable URLClassLoader isolated;
    private static boolean announced;

    private SqliteConnections() {}

    static @NotNull Connection open(@NotNull File file) throws SQLException {
        String url = "jdbc:sqlite:" + file.getAbsolutePath();
        URL jar = embeddedDriverJar();
        if (jar != null) {
            try {
                if (isolated == null) {
                    isolated = new URLClassLoader(new URL[]{jar}, ClassLoader.getPlatformClassLoader());
                    announce("pilote SQLite embarqué (classloader isolé)");
                }
                Class<?> bridge = isolated.loadClass(SqliteDriver.class.getName());
                return (Connection) bridge.getMethod("open", String.class).invoke(null, url);
            } catch (InvocationTargetException e) {
                throw new SQLException("ouverture SQLite isolée : " + file.getName(), e.getCause());
            } catch (ReflectiveOperationException | LinkageError e) {
                throw new SQLException("chargement du pilote SQLite isolé : " + file.getName(), e);
            }
        }
        try {
            // Le pilote du serveur ne s'enregistre pas toujours seul (pas de META-INF/services) :
            // chargement explicite, visible car le classloader du plugin délègue au serveur.
            Class.forName("org.sqlite.JDBC", true, SqliteConnections.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new SQLException("Aucun pilote SQLite : le serveur n'en fournit pas, "
                    + "shader org.xerial:sqlite-jdbc (sans relocation) dans le plugin.", e);
        }
        try {
            Connection c = DriverManager.getConnection(url);
            announce("pilote SQLite du serveur");
            return c;
        } catch (SQLException e) {
            throw new SQLException("ouverture SQLite (pilote du serveur) : " + file.getName(), e);
        }
    }

    /** @return le jar du plugin s'il contient sqlite-jdbc, sinon {@code null}. */
    private static @Nullable URL embeddedDriverJar() {
        try {
            CodeSource src = SqliteConnections.class.getProtectionDomain().getCodeSource();
            if (src == null) return null;
            URL own = src.getLocation();
            URL driver = SqliteConnections.class.getClassLoader().getResource("org/sqlite/JDBC.class");
            if (driver == null || !own.getPath().endsWith(".jar")) return null;
            return driver.toString().contains(own.getPath()) ? own : null;
        } catch (SecurityException e) {
            return null;
        }
    }

    private static void announce(@NotNull String what) {
        if (announced) return;
        announced = true;
        Log.info("SQLite : " + what);
    }

    /** Libère le classloader isolé (et la lib native) au shutdown. Thread: writer ou après son arrêt. */
    @org.jetbrains.annotations.ApiStatus.Internal
    public static void release() {
        URLClassLoader l = isolated;
        isolated = null;
        announced = false;
        if (l == null) return;
        try {
            l.close();
        } catch (IOException e) {
            Log.severe("fermeture du classloader SQLite isolé", e);
        }
    }
}
